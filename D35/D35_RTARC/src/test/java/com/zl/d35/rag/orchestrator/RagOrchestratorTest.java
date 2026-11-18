package com.zl.d35.rag.orchestrator;

import com.zl.d35.rag.assessment.AssessmentDecision;
import com.zl.d35.rag.assessment.EvidenceAssessmentResult;
import com.zl.d35.rag.assessment.EvidenceAssessmentService;
import com.zl.d35.rag.citation.CitationVerificationService;
import com.zl.d35.rag.context.ContextAssemblyResult;
import com.zl.d35.rag.context.ContextAssemblyService;
import com.zl.d35.rag.context.EvidenceItem;
import com.zl.d35.rag.dto.RagQuestionRequest;
import com.zl.d35.rag.generation.GenerationResult;
import com.zl.d35.rag.generation.GenerationService;
import com.zl.d35.rag.model.GenerationStatus;
import com.zl.d35.rag.model.RagErrorCode;
import com.zl.d35.rag.model.RetrievalStatus;
import com.zl.d35.rag.retrieval.RagRetrievalResult;
import com.zl.d35.rag.retrieval.RagRetrievalService;
import com.zl.d35.rag.validation.CitationValidationResult;
import com.zl.d35.rag.validation.ResponseValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagOrchestrator} 单元测试：响应前核验剔除、全失效受控失败、cited 回填、
 * 虚构 URL 拦截，以及非 ANSWER 时零模型调用。
 */
@ExtendWith(MockitoExtension.class)
class RagOrchestratorTest {

    @Mock private RagRetrievalService retrievalService;
    @Mock private ContextAssemblyService contextAssemblyService;
    @Mock private EvidenceAssessmentService evidenceAssessmentService;
    @Mock private GenerationService generationService;
    @Mock private ResponseValidationService responseValidationService;
    @Mock private CitationVerificationService citationVerificationService;

    @InjectMocks private RagOrchestrator orchestrator;

    private RagQuestionRequest request() {
        RagQuestionRequest req = new RagQuestionRequest();
        req.setQuestion("设备工作压力是多少");
        req.setTopK(5);
        return req;
    }

    private EvidenceItem item(String ref, String doc, String chunk) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(ref);
        item.setDocumentId(doc);
        item.setChunkId(chunk);
        item.setContent("工作压力 0.8");
        item.setTitle("设备手册");
        return item;
    }

    private ContextAssemblyResult context(List<EvidenceItem> items) {
        ContextAssemblyResult result = new ContextAssemblyResult();
        result.setEvidence(items);
        result.setContextText("ctx");
        return result;
    }

    private EvidenceAssessmentResult assessment(AssessmentDecision decision, RagErrorCode code) {
        return new EvidenceAssessmentResult(decision, code, "评估说明",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private void stubRetrievalAndAssembly(ContextAssemblyResult context) {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.SUCCESS, List.of(), null));
        when(contextAssemblyService.assemble(any())).thenReturn(context);
    }

    @Test
    @DisplayName("正常路径：cited 回填、稳定/请求级标识、url 为 null")
    void happyPath() {
        List<EvidenceItem> items = List.of(item("S1", "doc-1", "chunk-1"),
                item("S2", "doc-2", "chunk-2"));
        stubRetrievalAndAssembly(context(items));
        when(evidenceAssessmentService.assess(any(), any()))
                .thenReturn(assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE));
        when(generationService.generate(any(), any()))
                .thenReturn(GenerationResult.success("答案 [S1]", false, 5));
        when(citationVerificationService.verify(any()))
                .thenReturn(new CitationVerificationService.CitationVerification(
                        items, List.of(), Map.of()));
        when(citationVerificationService.resolveUrl(any())).thenReturn(null);
        // 模拟 markCited：回答中出现对应标记的来源才置 cited
        doAnswer(inv -> {
            String answerText = inv.getArgument(0);
            List<EvidenceItem> its = inv.getArgument(1);
            its.forEach(i -> i.setCited(answerText.contains("[" + i.getSourceRef() + "]")));
            return null;
        }).when(citationVerificationService).markCited(any(), any());
        when(responseValidationService.validate(any(), any()))
                .thenReturn(CitationValidationResult.pass());

        var response = orchestrator.ask(request());

        assertEquals("答案 [S1]", response.getAnswer());
        assertEquals(RagErrorCode.NONE, response.getErrorCode());
        assertEquals(2, response.getSources().size());
        assertEquals("doc-1", response.getSources().get(0).getSourceId());
        assertEquals("S1", response.getSources().get(0).getCitationId());
        assertTrue(response.getSources().get(0).isCited());
        assertNull(response.getSources().get(0).getUrl());
    }

    @Test
    @DisplayName("响应前核验剔除一个来源：sources 减少且记录说明")
    void partialRemoval() {
        List<EvidenceItem> items = List.of(item("S1", "doc-1", "chunk-1"),
                item("S2", "doc-2", "chunk-2"));
        stubRetrievalAndAssembly(context(items));
        when(evidenceAssessmentService.assess(any(), any()))
                .thenReturn(assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE));
        when(generationService.generate(any(), any()))
                .thenReturn(GenerationResult.success("答案 [S1]", false, 5));
        var removed = new CitationVerificationService.RemovedSource(
                "S2", "doc-2", "chunk-2", RagErrorCode.CITATION_TARGET_DELETED, "目标已删除");
        when(citationVerificationService.verify(any()))
                .thenReturn(new CitationVerificationService.CitationVerification(
                        List.of(items.get(0)), List.of(removed), Map.of()));
        when(citationVerificationService.resolveUrl(any())).thenReturn(null);
        when(responseValidationService.validate(any(), any()))
                .thenReturn(CitationValidationResult.pass());

        var response = orchestrator.ask(request());

        assertEquals(1, response.getSources().size());
        assertEquals("S1", response.getSources().get(0).getCitationId());
        assertTrue(response.getCitationNotes().stream().anyMatch(n -> n.contains("S2")));
    }

    @Test
    @DisplayName("全部来源失效：抑制答案，返回 CITATION_TARGET_DELETED")
    void allRemovedControlledFailure() {
        List<EvidenceItem> items = List.of(item("S1", "doc-1", "chunk-1"));
        stubRetrievalAndAssembly(context(items));
        when(evidenceAssessmentService.assess(any(), any()))
                .thenReturn(assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE));
        when(generationService.generate(any(), any()))
                .thenReturn(GenerationResult.success("答案 [S1]", false, 5));
        var removed = new CitationVerificationService.RemovedSource(
                "S1", "doc-1", "chunk-1", RagErrorCode.CITATION_TARGET_DELETED, "目标已删除");
        when(citationVerificationService.verify(any()))
                .thenReturn(new CitationVerificationService.CitationVerification(
                        List.of(), List.of(removed), Map.of()));

        var response = orchestrator.ask(request());

        assertNull(response.getAnswer());
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED, response.getErrorCode());
        assertTrue(response.getSources().isEmpty());
        verify(citationVerificationService, never()).markCited(any(), any());
    }

    @Test
    @DisplayName("校验发现虚构 URL：返回 FABRICATED_URL 受控失败")
    void fabricatedUrlFailure() {
        List<EvidenceItem> items = List.of(item("S1", "doc-1", "chunk-1"));
        stubRetrievalAndAssembly(context(items));
        when(evidenceAssessmentService.assess(any(), any()))
                .thenReturn(assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE));
        when(generationService.generate(any(), any()))
                .thenReturn(GenerationResult.success("答案 [S1]", false, 5));
        when(citationVerificationService.verify(any()))
                .thenReturn(new CitationVerificationService.CitationVerification(
                        items, List.of(), Map.of()));
        when(citationVerificationService.resolveUrl(any())).thenReturn(null);
        when(responseValidationService.validate(any(), any()))
                .thenReturn(new CitationValidationResult(true, List.of(), List.of(),
                        List.of("javascript:alert(1)"), false));

        var response = orchestrator.ask(request());

        assertNull(response.getAnswer());
        assertEquals(RagErrorCode.FABRICATED_URL, response.getErrorCode());
    }

    @Test
    @DisplayName("全部来源归属不一致：抑制答案，返回 CITATION_OWNERSHIP_MISMATCH")
    void ownershipMismatchControlledFailure() {
        List<EvidenceItem> items = List.of(item("S1", "doc-1", "chunk-1"));
        stubRetrievalAndAssembly(context(items));
        when(evidenceAssessmentService.assess(any(), any()))
                .thenReturn(assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE));
        when(generationService.generate(any(), any()))
                .thenReturn(GenerationResult.success("答案 [S1]", false, 5));
        var removed = new CitationVerificationService.RemovedSource(
                "S1", "doc-1", "chunk-1", RagErrorCode.CITATION_OWNERSHIP_MISMATCH, "归属不一致");
        when(citationVerificationService.verify(any()))
                .thenReturn(new CitationVerificationService.CitationVerification(
                        List.of(), List.of(removed), Map.of()));

        var response = orchestrator.ask(request());

        assertNull(response.getAnswer());
        assertEquals(RagErrorCode.CITATION_OWNERSHIP_MISMATCH, response.getErrorCode());
        assertTrue(response.getSources().isEmpty());
    }

    @Test
    @DisplayName("Claim-Citation 错配：校验失败并回填 mismatch 明细")
    void claimCitationMismatchFailure() {
        List<EvidenceItem> items = List.of(item("S1", "doc-1", "chunk-1"));
        stubRetrievalAndAssembly(context(items));
        when(evidenceAssessmentService.assess(any(), any()))
                .thenReturn(assessment(AssessmentDecision.ANSWER, RagErrorCode.NONE));
        when(generationService.generate(any(), any()))
                .thenReturn(GenerationResult.success("答案 工作压力 0.8 [S1]", false, 5));
        when(citationVerificationService.verify(any()))
                .thenReturn(new CitationVerificationService.CitationVerification(
                        items, List.of(), Map.of()));
        when(citationVerificationService.resolveUrl(any())).thenReturn(null);
        when(responseValidationService.validate(any(), any()))
                .thenReturn(new CitationValidationResult(true, List.of(),
                        List.of("工作压力=0.8"), List.of(), false,
                        List.of("工作压力=0.8 不被 [S1] 支持")));

        var response = orchestrator.ask(request());

        assertNull(response.getAnswer());
        assertEquals(RagErrorCode.GENERATION_VALIDATION_FAILED, response.getErrorCode());
        assertEquals(1, response.getCitationValidation().getClaimCitationMismatches().size());
    }

    @Test
    @DisplayName("非 ANSWER 决策：不调用生成与核验")
    void nonAnswerSkipsGeneration() {
        List<EvidenceItem> items = List.of(item("S1", "doc-1", "chunk-1"));
        stubRetrievalAndAssembly(context(items));
        when(evidenceAssessmentService.assess(any(), any()))
                .thenReturn(assessment(AssessmentDecision.REFUSE, RagErrorCode.NO_EVIDENCE));

        var response = orchestrator.ask(request());

        assertEquals(GenerationStatus.SKIPPED_NO_EVIDENCE, response.getGenerationStatus());
        verify(generationService, never()).generate(any(), any());
        verify(citationVerificationService, never()).verify(any());
    }
}
