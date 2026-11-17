package com.zl.d34.rag.orchestrator;

import com.zl.d34.rag.assessment.AssessmentDecision;
import com.zl.d34.rag.assessment.ConflictGroup;
import com.zl.d34.rag.assessment.EvidenceAssessmentResult;
import com.zl.d34.rag.context.ContextAssemblyResult;
import com.zl.d34.rag.context.EvidenceItem;
import com.zl.d34.rag.dto.RagAnswerResponse;
import com.zl.d34.rag.dto.RagQuestionRequest;
import com.zl.d34.rag.generation.GenerationResult;
import com.zl.d34.rag.generation.GenerationService;
import com.zl.d34.rag.context.ContextAssemblyService;
import com.zl.d34.rag.model.GenerationStatus;
import com.zl.d34.rag.model.RagErrorCode;
import com.zl.d34.rag.model.RetrievalStatus;
import com.zl.d34.rag.retrieval.RagRetrievalResult;
import com.zl.d34.rag.retrieval.RagRetrievalService;
import com.zl.d34.rag.validation.CitationValidationResult;
import com.zl.d34.rag.validation.ResponseValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagOrchestrator} 单元测试：四类风险场景、零模型调用证据与生成后校验受控返回。
 */
@ExtendWith(MockitoExtension.class)
class RagOrchestratorTest {

    @Mock
    private RagRetrievalService retrievalService;
    @Mock
    private ContextAssemblyService contextAssemblyService;
    @Mock
    private com.zl.d34.rag.assessment.EvidenceAssessmentService evidenceAssessmentService;
    @Mock
    private GenerationService generationService;
    @Mock
    private ResponseValidationService responseValidationService;

    @InjectMocks
    private RagOrchestrator orchestrator;

    @Test
    void emptyRetrievalShouldRefuseWithoutModelCall() {
        stubRetrieval(RetrievalStatus.EMPTY, List.of());
        ContextAssemblyResult context = new ContextAssemblyResult();
        when(contextAssemblyService.assemble(any())).thenReturn(context);

        RagAnswerResponse response = orchestrator.ask(request("未收录设备的参数是多少"));

        assertThat(response.getGenerationStatus()).isEqualTo(GenerationStatus.SKIPPED_NO_EVIDENCE);
        assertThat(response.getErrorCode()).isEqualTo(RagErrorCode.NO_EVIDENCE);
        verify(generationService, never()).generate(anyString(), any());
    }

    @Test
    void retrievalFailureShouldShortCircuit() {
        stubRetrieval(RetrievalStatus.FAILED, List.of());

        RagAnswerResponse response = orchestrator.ask(request("任意问题"));

        assertThat(response.getRetrievalStatus()).isEqualTo(RetrievalStatus.FAILED);
        assertThat(response.getGenerationStatus()).isEqualTo(GenerationStatus.NOT_ATTEMPTED);
        assertThat(response.getErrorCode()).isEqualTo(RagErrorCode.RETRIEVAL_FAILED);
        verify(contextAssemblyService, never()).assemble(any());
        verify(generationService, never()).generate(anyString(), any());
    }

    @Test
    void lowRelevanceShouldRefuseWithoutModelCall() {
        EvidenceItem item = evidence("S1");
        stubRetrieval(RetrievalStatus.SUCCESS, List.of());
        stubContextWithEvidence(List.of(item));
        when(evidenceAssessmentService.assess(any(), anyString())).thenReturn(
                assessment(AssessmentDecision.REFUSE, RagErrorCode.LOW_RELEVANCE,
                        List.of(), List.of("S1"), List.of(), List.of(), "仅关键词相似"));

        RagAnswerResponse response = orchestrator.ask(request("某问题"));

        assertThat(response.getDecision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(response.getErrorCode()).isEqualTo(RagErrorCode.LOW_RELEVANCE);
        verify(generationService, never()).generate(anyString(), any());
    }

    @Test
    void conflictShouldReturnConflictWithoutModelCall() {
        EvidenceItem item = evidence("S1");
        stubRetrieval(RetrievalStatus.SUCCESS, List.of());
        stubContextWithEvidence(List.of(item));
        ConflictGroup group = new ConflictGroup("A型水泵", "额定压力",
                List.of("0.8", "0.6"), List.of("S1", "S2"));
        when(evidenceAssessmentService.assess(any(), anyString())).thenReturn(
                assessment(AssessmentDecision.CONFLICT, RagErrorCode.EVIDENCE_CONFLICT,
                        List.of("S1", "S2"), List.of(), List.of(group), List.of(), "证据冲突"));

        RagAnswerResponse response = orchestrator.ask(request("A型水泵额定压力是多少"));

        assertThat(response.getGenerationStatus()).isEqualTo(GenerationStatus.CONFLICT);
        assertThat(response.getErrorCode()).isEqualTo(RagErrorCode.EVIDENCE_CONFLICT);
        assertThat(response.getConflictGroups()).hasSize(1);
        verify(generationService, never()).generate(anyString(), any());
    }

    @Test
    void clarifyShouldReturnSingleQuestionWithoutModelCall() {
        EvidenceItem item = evidence("S1");
        stubRetrieval(RetrievalStatus.SUCCESS, List.of());
        stubContextWithEvidence(List.of(item));
        when(evidenceAssessmentService.assess(any(), anyString())).thenReturn(
                assessment(AssessmentDecision.CLARIFY, RagErrorCode.MISSING_REQUIRED_CONTEXT,
                        List.of("S1"), List.of(), List.of(), List.of("设备型号"),
                        "请先提供设备的具体型号"));

        RagAnswerResponse response = orchestrator.ask(request("水泵压力是多少"));

        assertThat(response.getGenerationStatus()).isEqualTo(GenerationStatus.CLARIFY);
        assertThat(response.getClarificationQuestion()).isEqualTo("请先提供设备的具体型号");
        verify(generationService, never()).generate(anyString(), any());
    }

    @Test
    void injectionDocumentIsTreatedAsDataAndAnsweredNormally() {
        // 证据正文含注入指令，但只作为不可信数据；评估仍 ANSWER，生成正常返回
        EvidenceItem item = evidence("S1");
        stubRetrieval(RetrievalStatus.SUCCESS, List.of());
        stubContextWithEvidence(List.of(item));
        when(evidenceAssessmentService.assess(any(), anyString())).thenReturn(
                assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE,
                        List.of("S1"), List.of(), List.of(), List.of(), "证据充分"));
        GenerationResult generation = GenerationResult.success(
                "根据资料，额定压力为 0.8 MPa [S1]", false, 10L);
        when(generationService.generate(anyString(), any())).thenReturn(generation);
        when(responseValidationService.validate(anyString(), any()))
                .thenReturn(CitationValidationResult.pass());

        RagAnswerResponse response = orchestrator.ask(request("A型水泵额定压力是多少"));

        assertThat(response.getGenerationStatus()).isEqualTo(GenerationStatus.SUCCESS);
        assertThat(response.getAnswer()).contains("额定压力为 0.8 MPa");
        // 注入文本没有改变角色/规则：生成仍只被调用一次，且引用合法
        verify(generationService, org.mockito.Mockito.times(1)).generate(anyString(), any());
        assertThat(response.getCitationValidation().isValidationPassed()).isTrue();
    }

    @Test
    void failedPostValidationShouldBlockOutput() {
        EvidenceItem item = evidence("S1");
        stubRetrieval(RetrievalStatus.SUCCESS, List.of());
        stubContextWithEvidence(List.of(item));
        when(evidenceAssessmentService.assess(any(), anyString())).thenReturn(
                assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE,
                        List.of("S1"), List.of(), List.of(), List.of(), "证据充分"));
        GenerationResult generation = GenerationResult.success(
                "额定压力为 0.9 MPa [S1]", false, 10L);
        when(generationService.generate(anyString(), any())).thenReturn(generation);
        CitationValidationResult validation = new CitationValidationResult(
                true, List.of(), List.of("额定压力=0.9"), false);
        when(responseValidationService.validate(anyString(), any())).thenReturn(validation);

        RagAnswerResponse response = orchestrator.ask(request("A型水泵额定压力是多少"));

        assertThat(response.getErrorCode()).isEqualTo(RagErrorCode.GENERATION_VALIDATION_FAILED);
        assertThat(response.getAnswer()).isNull();
        assertThat(response.getCitationValidation().isValidationPassed()).isFalse();
    }

    private void stubRetrieval(RetrievalStatus status, List<?> matches) {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(status, List.of(), "受控失败提示"));
    }

    private void stubContextWithEvidence(List<EvidenceItem> items) {
        ContextAssemblyResult context = new ContextAssemblyResult();
        context.setEvidence(items);
        when(contextAssemblyService.assemble(any())).thenReturn(context);
    }

    private EvidenceAssessmentResult assessment(AssessmentDecision decision, RagErrorCode code,
                                                List<String> accepted, List<String> rejected,
                                                List<ConflictGroup> conflicts,
                                                List<String> missing, String reason) {
        return new EvidenceAssessmentResult(
                decision, code, reason,
                List.copyOf(java.util.stream.Stream.concat(accepted.stream(), rejected.stream())
                        .toList()),
                accepted, rejected, conflicts, missing, List.of());
    }

    private EvidenceItem evidence(String ref) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(ref);
        item.setDocumentId("doc1");
        item.setTitle("A型水泵");
        item.setContent("A型水泵额定压力为 0.8 MPa");
        return item;
    }

    private RagQuestionRequest request(String question) {
        RagQuestionRequest request = new RagQuestionRequest();
        request.setQuestion(question);
        return request;
    }
}
