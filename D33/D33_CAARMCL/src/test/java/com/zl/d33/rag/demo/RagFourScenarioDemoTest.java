package com.zl.d33.rag.demo;

import com.zl.d33.document.retrieval.dto.SimilarityMatchView;
import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.context.ContextAssemblyService;
import com.zl.d33.rag.dto.RagAnswerResponse;
import com.zl.d33.rag.dto.RagQuestionRequest;
import com.zl.d33.rag.generation.GenerationService;
import com.zl.d33.rag.model.GenerationStatus;
import com.zl.d33.rag.model.RagErrorCode;
import com.zl.d33.rag.model.RetrievalStatus;
import com.zl.d33.rag.orchestrator.RagOrchestrator;
import com.zl.d33.rag.prompt.RagPromptBuilder;
import com.zl.d33.rag.retrieval.RagRetrievalResult;
import com.zl.d33.rag.retrieval.RagRetrievalService;
import com.zl.d33.resilience.ModelErrorCategory;
import com.zl.d33.resilience.ModelInvocationException;
import com.zl.d33.resilience.ModelResilienceSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D33 四类端到端场景受控演示（真实编排 + 真实组装 + 真实生成 + 真实 Prompt，
 * 仅对“检索基础设施”和“远程模型/韧性边界”打桩）。
 *
 * <p><b>口径声明</b>：这是无外部依赖即可复现的受控端到端（stub ChatModel），
 * 不是 L5 真实 PG + 真实 ChatModel；真实端到端需用户提供 PG_VECTOR_PASSWORD 与
 * AI_API_KEY 并以 AI_MODE=deepseek 启动后运行演示脚本。</p>
 */
@ExtendWith(MockitoExtension.class)
class RagFourScenarioDemoTest {

    @Mock
    private RagRetrievalService retrievalService;

    @Mock
    private ChatModel chatModel;

    @Mock
    private ModelResilienceSupport resilienceSupport;

    private RagOrchestrator pipeline() {
        RagProperties properties = new RagProperties();
        ContextAssemblyService assemblyService = new ContextAssemblyService(properties);
        GenerationService generationService = new GenerationService(
                Optional.of(chatModel), resilienceSupport, new RagPromptBuilder());
        return new RagOrchestrator(retrievalService, assemblyService, generationService);
    }

    private RagQuestionRequest request(String question) {
        RagQuestionRequest req = new RagQuestionRequest();
        req.setQuestion(question);
        return req;
    }

    private SimilarityMatchView evidence(String chunkId, int index, String content) {
        return SimilarityMatchView.from(chunkId, content, Map.of(
                "documentId", "d33-demo-cavitation",
                "title", "水泵气蚀维修手册",
                "version", 1,
                "chunkIndex", index,
                "strategy", "STRUCTURE"), 0.25 + index * 0.05, null);
    }

    private ChatResponse modelAnswer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private void print(String title, RagAnswerResponse r) {
        System.out.println("\n===== " + title + " =====");
        System.out.println("retrieval=" + r.getRetrievalStatus()
                + ", generation=" + r.getGenerationStatus()
                + ", code=" + r.getErrorCode()
                + ", evidence=" + r.getEvidenceCount()
                + ", unknownCitation=" + r.isUnknownCitationDetected());
        System.out.println("answer=" + r.getAnswer());
    }

    @Test
    @DisplayName("场景1 文档可答：有证据且模型基于 [S1] 回答")
    void scenario1Answerable() {
        when(retrievalService.retrieve(any())).thenReturn(new RagRetrievalResult(
                RetrievalStatus.SUCCESS,
                List.of(evidence("chunk-1", 0, "水泵气蚀表现为噪声、振动与流量下降，应检查入口压力与吸入高度。"),
                        evidence("chunk-2", 1, "处理气蚀需降低吸入高度、排除入口漏气并更换磨损叶轮。")),
                null));
        when(resilienceSupport.executeBlocking(any())).thenReturn(
                modelAnswer("根据 [S1]，气蚀表现为噪声、振动与流量下降；按 [S2] 应降低吸入高度并排除入口漏气。"));

        RagAnswerResponse r = pipeline().ask(request("水泵气蚀有哪些表现，如何处理？"));
        print("场景1 文档可答", r);

        assertEquals(GenerationStatus.SUCCESS, r.getGenerationStatus());
        assertEquals(2, r.getEvidenceCount());
        assertTrue(r.getAnswer().contains("[S1]"));
        assertEquals(RagErrorCode.NONE, r.getErrorCode());
    }

    @Test
    @DisplayName("场景2 无关问题：模型依据系统规则受控说明证据不足，不补造")
    void scenario2Irrelevant() {
        when(retrievalService.retrieve(any())).thenReturn(new RagRetrievalResult(
                RetrievalStatus.SUCCESS, List.of(evidence("chunk-1", 0, "水泵气蚀相关内容。")), null));
        when(resilienceSupport.executeBlocking(any())).thenReturn(
                modelAnswer("知识库证据不足，无法回答该问题。"));

        RagAnswerResponse r = pipeline().ask(request("今天天气适合出去玩吗？"));
        print("场景2 无关问题", r);

        assertEquals(GenerationStatus.SUCCESS, r.getGenerationStatus());
        assertTrue(r.getAnswer().contains("证据不足"));
    }

    @Test
    @DisplayName("场景3 空检索：零命中，不调用 ChatModel（调用次数可核验为 0）")
    void scenario3EmptyRetrieval() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.EMPTY, List.of(), null));

        RagAnswerResponse r = pipeline().ask(request("一个知识库完全不覆盖的问题？"));
        print("场景3 空检索", r);

        assertEquals(RetrievalStatus.EMPTY, r.getRetrievalStatus());
        assertEquals(GenerationStatus.SKIPPED_NO_EVIDENCE, r.getGenerationStatus());
        assertEquals(RagErrorCode.NO_EVIDENCE, r.getErrorCode());
        // 可核验证据：模型一次都没被调用
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("场景4 模型超时：受控 FAILED_TIMEOUT，不伪装成功")
    void scenario4Timeout() {
        when(retrievalService.retrieve(any())).thenReturn(new RagRetrievalResult(
                RetrievalStatus.SUCCESS, List.of(evidence("chunk-1", 0, "气蚀处理内容。")), null));
        when(resilienceSupport.executeBlocking(any())).thenThrow(
                new ModelInvocationException(ModelErrorCategory.READ_TIMEOUT,
                        "read timeout", new RuntimeException()));

        RagAnswerResponse r = pipeline().ask(request("气蚀如何处理？"));
        print("场景4 模型超时", r);

        assertEquals(GenerationStatus.FAILED_TIMEOUT, r.getGenerationStatus());
        assertEquals(RagErrorCode.GENERATION_TIMEOUT, r.getErrorCode());
        assertNull(r.getAnswer());
    }

    @Test
    @DisplayName("补充区分：检索失败 → NOT_ATTEMPTED，与空检索和生成失败区分")
    void retrievalFailureIsDistinct() {
        when(retrievalService.retrieve(any())).thenReturn(
                new RagRetrievalResult(RetrievalStatus.FAILED, List.of(), "检索失败"));

        RagAnswerResponse r = pipeline().ask(request("问题"));
        print("补充 检索失败", r);

        assertEquals(RetrievalStatus.FAILED, r.getRetrievalStatus());
        assertEquals(GenerationStatus.NOT_ATTEMPTED, r.getGenerationStatus());
        assertEquals(RagErrorCode.RETRIEVAL_FAILED, r.getErrorCode());
        verify(chatModel, never()).call(any(Prompt.class));
    }
}
