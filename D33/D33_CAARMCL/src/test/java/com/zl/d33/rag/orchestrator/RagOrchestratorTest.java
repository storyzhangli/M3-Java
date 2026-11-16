package com.zl.d33.rag.orchestrator;

import com.zl.d33.document.retrieval.dto.SimilarityMatchView;
import com.zl.d33.rag.context.ContextAssemblyResult;
import com.zl.d33.rag.context.EvidenceItem;
import com.zl.d33.rag.dto.RagAnswerResponse;
import com.zl.d33.rag.dto.RagQuestionRequest;
import com.zl.d33.rag.generation.GenerationResult;
import com.zl.d33.rag.generation.GenerationService;
import com.zl.d33.rag.context.ContextAssemblyService;
import com.zl.d33.rag.model.GenerationStatus;
import com.zl.d33.rag.model.RagErrorCode;
import com.zl.d33.rag.model.RetrievalStatus;
import com.zl.d33.rag.retrieval.RagRetrievalResult;
import com.zl.d33.rag.retrieval.RagRetrievalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 编排器单测：分支边界与“空检索/无证据不调用生成”的可核验证据。
 */
@ExtendWith(MockitoExtension.class)
class RagOrchestratorTest {

    @Mock
    private RagRetrievalService retrievalService;

    @Mock
    private ContextAssemblyService contextAssemblyService;

    @Mock
    private GenerationService generationService;

    private RagOrchestrator orchestrator() {
        return new RagOrchestrator(retrievalService, contextAssemblyService, generationService);
    }

    private RagQuestionRequest request() {
        RagQuestionRequest req = new RagQuestionRequest();
        req.setQuestion("如何处理气蚀？");
        return req;
    }

    private ContextAssemblyResult contextWithEvidence() {
        ContextAssemblyResult ctx = new ContextAssemblyResult();
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef("S1");
        item.setChunkId("c1");
        ctx.setEvidence(List.of(item));
        ctx.setContextText("[S1] ...");
        return ctx;
    }

    @Test
    @DisplayName("有证据且生成成功 → 完整回答与来源")
    void shouldReturnAnswerOnHappyPath() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.SUCCESS, List.of(mockMatch()), null));
        when(contextAssemblyService.assemble(any())).thenReturn(contextWithEvidence());
        when(generationService.generate(any(), any())).thenReturn(
                GenerationResult.success("根据证据回答", false, 5));

        RagAnswerResponse response = orchestrator().ask(request());

        assertEquals(RetrievalStatus.SUCCESS, response.getRetrievalStatus());
        assertEquals(GenerationStatus.SUCCESS, response.getGenerationStatus());
        assertEquals("根据证据回答", response.getAnswer());
        assertEquals(1, response.getEvidenceCount());
        assertEquals(RagErrorCode.NONE, response.getErrorCode());
    }

    @Test
    @DisplayName("空检索 → 不调用生成，状态 SKIPPED_NO_EVIDENCE")
    void shouldSkipGenerationOnEmptyRetrieval() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.EMPTY, List.of(), null));
        when(contextAssemblyService.assemble(any())).thenReturn(new ContextAssemblyResult());

        RagAnswerResponse response = orchestrator().ask(request());

        assertEquals(RetrievalStatus.EMPTY, response.getRetrievalStatus());
        assertEquals(GenerationStatus.SKIPPED_NO_EVIDENCE, response.getGenerationStatus());
        assertEquals(RagErrorCode.NO_EVIDENCE, response.getErrorCode());
        // 可核验证据：生成服务（进而 ChatModel）调用次数为 0
        verify(generationService, never()).generate(any(), any());
    }

    @Test
    @DisplayName("检索成功但全部被过滤为无证据 → 不调用生成")
    void shouldSkipWhenNoValidEvidence() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.SUCCESS, List.of(mockMatch()), null));
        when(contextAssemblyService.assemble(any())).thenReturn(new ContextAssemblyResult());

        RagAnswerResponse response = orchestrator().ask(request());

        assertEquals(GenerationStatus.SKIPPED_NO_EVIDENCE, response.getGenerationStatus());
        verify(generationService, never()).generate(any(), any());
    }

    @Test
    @DisplayName("检索失败 → 短路，不组装不生成，状态 NOT_ATTEMPTED")
    void shouldShortCircuitOnRetrievalFailure() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.FAILED, List.of(), "检索失败"));

        RagAnswerResponse response = orchestrator().ask(request());

        assertEquals(RetrievalStatus.FAILED, response.getRetrievalStatus());
        assertEquals(GenerationStatus.NOT_ATTEMPTED, response.getGenerationStatus());
        assertEquals(RagErrorCode.RETRIEVAL_FAILED, response.getErrorCode());
        verify(contextAssemblyService, never()).assemble(any());
        verify(generationService, never()).generate(any(), any());
    }

    @Test
    @DisplayName("生成失败 → 受控错误，answer 为 null，来源仍返回")
    void shouldReturnControlledGenerationFailure() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.SUCCESS, List.of(mockMatch()), null));
        when(contextAssemblyService.assemble(any())).thenReturn(contextWithEvidence());
        when(generationService.generate(any(), any())).thenReturn(
                GenerationResult.failure(GenerationStatus.FAILED_TIMEOUT,
                        RagErrorCode.GENERATION_TIMEOUT, "超时", 5));

        RagAnswerResponse response = orchestrator().ask(request());

        assertEquals(GenerationStatus.FAILED_TIMEOUT, response.getGenerationStatus());
        assertEquals(RagErrorCode.GENERATION_TIMEOUT, response.getErrorCode());
        assertNull(response.getAnswer());
        assertEquals(1, response.getSources().size());
    }

    private SimilarityMatchView mockMatch() {
        return SimilarityMatchView.from("c1", "内容", java.util.Map.of(
                "documentId", "doc-1", "title", "文档", "version", 1,
                "chunkIndex", 0, "strategy", "STRUCTURE"), 0.2, null);
    }
}
