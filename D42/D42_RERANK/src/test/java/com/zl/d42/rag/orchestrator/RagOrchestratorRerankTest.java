package com.zl.d42.rag.orchestrator;

import com.zl.d42.document.retrieval.dto.SimilarityMatchView;
import com.zl.d42.rag.assessment.AssessmentDecision;
import com.zl.d42.rag.assessment.EvidenceAssessmentResult;
import com.zl.d42.rag.assessment.EvidenceAssessmentService;
import com.zl.d42.rag.citation.CitationVerificationService;
import com.zl.d42.rag.context.ContextAssemblyResult;
import com.zl.d42.rag.context.ContextAssemblyService;
import com.zl.d42.rag.context.EvidenceItem;
import com.zl.d42.rag.dto.RagAnswerResponse;
import com.zl.d42.rag.dto.RagQuestionRequest;
import com.zl.d42.rag.generation.GenerationResult;
import com.zl.d42.rag.generation.GenerationService;
import com.zl.d42.rag.model.RagErrorCode;
import com.zl.d42.rag.model.RetrievalStatus;
import com.zl.d42.rag.rerank.RerankCandidate;
import com.zl.d42.rag.rerank.RerankFlags;
import com.zl.d42.rag.rerank.RerankPort;
import com.zl.d42.rag.rerank.RerankResult;
import com.zl.d42.rag.rerank.RerankUsage;
import com.zl.d42.rag.retrieval.RagRetrievalResult;
import com.zl.d42.rag.retrieval.RagRetrievalService;
import com.zl.d42.rag.validation.CitationValidationResult;
import com.zl.d42.rag.validation.ResponseValidationService;
import com.zl.d42.rag.dto.RagBudgetView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排层 rerank 挂点单测（P2/C01，H1/H7/H8 的代码级守卫）：
 * 端口缺席=原序直通且零观测字段；重排真实生效且观测回填；Port 异常=防御回退原序；
 * 检索 FAILED 短路时精排步根本不被触碰（预门禁/失败语义不碰）。
 */
class RagOrchestratorRerankTest {

    private RagRetrievalService retrievalService;
    private ContextAssemblyService assemblyService;
    private EvidenceAssessmentService assessmentService;
    private GenerationService generationService;
    private ResponseValidationService validationService;
    private CitationVerificationService citationService;
    private ObjectProvider<RerankPort> provider;
    private RagOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        retrievalService = mock(RagRetrievalService.class);
        assemblyService = mock(ContextAssemblyService.class);
        assessmentService = mock(EvidenceAssessmentService.class);
        generationService = mock(GenerationService.class);
        validationService = mock(ResponseValidationService.class);
        citationService = mock(CitationVerificationService.class);
        provider = mock(ObjectProvider.class);
        orchestrator = new RagOrchestrator(retrievalService, assemblyService, assessmentService,
                generationService, validationService, citationService, provider);

        // 组装：真实结果对象，evidence 与入参同序（重排是否传导到此即断言点）
        when(assemblyService.assemble(anyList())).thenAnswer(inv -> {
            List<SimilarityMatchView> in = inv.getArgument(0);
            ContextAssemblyResult r = new ContextAssemblyResult();
            List<EvidenceItem> items = new ArrayList<>();
            for (SimilarityMatchView m : in) {
                EvidenceItem it = new EvidenceItem();
                it.setChunkId(m.getChunkId());
                it.setContent(m.getContent());
                it.setSourceRef("S-" + m.getChunkId());
                it.setDocumentId("doc");
                items.add(it);
            }
            r.setEvidence(items);
            r.setContextText("ctx");
            r.setBudget(new RagBudgetView());
            return r;
        });
        when(assessmentService.assess(anyList(), any())).thenReturn(new EvidenceAssessmentResult(
                AssessmentDecision.ANSWER, RagErrorCode.NONE, "ok",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(generationService.generate(any(), any()))
                .thenReturn(GenerationResult.success("回答", false, 1L));
        when(citationService.verify(any())).thenAnswer(inv -> {
            List<EvidenceItem> items = inv.getArgument(0);
            return new CitationVerificationService.CitationVerification(items, List.of(), Map.of());
        });
        when(validationService.validate(any(), anyList())).thenReturn(new CitationValidationResult(
                true, List.of(), List.of(), List.of(), true, List.of()));
    }

    private static SimilarityMatchView view(String chunkId, String content) {
        SimilarityMatchView v = new SimilarityMatchView();
        v.setChunkId(chunkId);
        v.setContent(content);
        return v;
    }

    private void retrievalSuccess(List<SimilarityMatchView> matches) {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.SUCCESS, matches, null));
    }

    private static RagQuestionRequest request() {
        RagQuestionRequest req = new RagQuestionRequest();
        req.setQuestion("冷却流量上限是多少");
        return req;
    }

    @SuppressWarnings("unchecked")
    private List<SimilarityMatchView> capturedAssembleArg() {
        ArgumentCaptor<List<SimilarityMatchView>> captor = ArgumentCaptor.forClass(List.class);
        verify(assemblyService).assemble(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("端口缺席（默认态）：assemble 收到原序 + budget/timing 无 rerank 观测（H1 零差异）")
    void portAbsentIsByteIdentical() {
        retrievalSuccess(List.of(view("A", "甲"), view("B", "乙")));
        when(provider.getIfAvailable()).thenReturn(null);

        RagAnswerResponse resp = orchestrator.ask(request());

        assertEquals(List.of("A", "B"),
                capturedAssembleArg().stream().map(SimilarityMatchView::getChunkId).toList());
        assertNull(resp.getBudget().getRerankAdapter());
        assertNull(resp.getBudget().getRerankFlags());
        assertEquals(0L, resp.getTiming().getRerankMs());
        assertEquals(RagErrorCode.NONE, resp.getErrorCode());
    }

    @Test
    @DisplayName("端口重排：assemble 收到新序且集合成员不变，观测字段回填（D2 执行点）")
    void portReorderPropagatesAndObserved() {
        retrievalSuccess(List.of(view("A", "甲"), view("B", "乙")));
        RerankPort port = mock(RerankPort.class);
        when(port.rerank(any(), anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new RerankResult("local",
                        List.of(new RerankResult.Item(1, 0, 0.9d),
                                new RerankResult.Item(0, 1, 0.1d)),
                        RerankUsage.zero(5), 3L, EnumSet.of(RerankFlags.REORDERED)));
        when(provider.getIfAvailable()).thenReturn(port);

        RagAnswerResponse resp = orchestrator.ask(request());

        assertEquals(List.of("B", "A"),
                capturedAssembleArg().stream().map(SimilarityMatchView::getChunkId).toList());
        assertEquals("local", resp.getBudget().getRerankAdapter());
        assertEquals("REORDERED", resp.getBudget().getRerankFlags());
        assertEquals(0, resp.getBudget().getRerankCalls());
        assertEquals(5, resp.getBudget().getRerankEstimatedTokens());
    }

    @Test
    @DisplayName("Port 抛异常：防御回退原序 + FAILED_OPEN + defensive，检索语义零触碰（H8）")
    void portThrowsFailsOpen() {
        retrievalSuccess(List.of(view("A", "甲"), view("B", "乙")));
        RerankPort port = mock(RerankPort.class);
        when(port.rerank(any(), anyList(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("boom"));
        when(provider.getIfAvailable()).thenReturn(port);

        RagAnswerResponse resp = orchestrator.ask(request());

        assertEquals(List.of("A", "B"),
                capturedAssembleArg().stream().map(SimilarityMatchView::getChunkId).toList());
        assertEquals("defensive", resp.getBudget().getRerankAdapter());
        assertTrue(resp.getBudget().getRerankFlags().contains("FAILED_OPEN"));
        assertEquals(RagErrorCode.NONE, resp.getErrorCode());
    }

    @Test
    @DisplayName("检索 FAILED：短路在挂点之前，端口提供者完全未被触碰（H7 失败语义不碰）")
    void failedShortCircuitNeverTouchesPort() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.FAILED, List.of(), "上游失败"));

        RagAnswerResponse resp = orchestrator.ask(request());

        assertEquals(RagErrorCode.RETRIEVAL_FAILED, resp.getErrorCode());
        verify(provider, never()).getIfAvailable();
    }
}
