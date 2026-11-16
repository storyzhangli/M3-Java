package com.zl.d33.rag.generation;

import com.zl.d33.rag.context.ContextAssemblyResult;
import com.zl.d33.rag.context.EvidenceItem;
import com.zl.d33.rag.model.GenerationStatus;
import com.zl.d33.rag.model.RagErrorCode;
import com.zl.d33.rag.prompt.RagPromptBuilder;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 生成服务单测：成功、未配置、超时、异常、空响应与引用核验。
 */
@ExtendWith(MockitoExtension.class)
class GenerationServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ModelResilienceSupport resilienceSupport;

    private final RagPromptBuilder promptBuilder = new RagPromptBuilder();

    private GenerationService service(Optional<ChatModel> model) {
        return new GenerationService(model, resilienceSupport, promptBuilder);
    }

    private ContextAssemblyResult contextWithRefs(String... refs) {
        ContextAssemblyResult ctx = new ContextAssemblyResult();
        List<EvidenceItem> items = new ArrayList<>();
        for (String ref : refs) {
            EvidenceItem item = new EvidenceItem();
            item.setSourceRef(ref);
            items.add(item);
        }
        ctx.setEvidence(items);
        ctx.setContextText("context");
        return ctx;
    }

    private ChatResponse chatResponseWith(String text) {
        // 使用真实消息对象，避免对 final 方法 AssistantMessage.getText() 打桩。
        AssistantMessage message = new AssistantMessage(text);
        Generation generation = new Generation(message);
        return new ChatResponse(List.of(generation));
    }

    @Test
    @DisplayName("模型正常返回非空回答 → SUCCESS")
    void shouldReturnSuccess() {
        when(resilienceSupport.executeBlocking(any()))
                .thenReturn(chatResponseWith("根据 [S1]，设备正常。"));

        GenerationResult result = service(Optional.of(chatModel))
                .generate("问题", contextWithRefs("S1"));

        assertEquals(GenerationStatus.SUCCESS, result.status());
        assertEquals("根据 [S1]，设备正常。", result.content());
        assertFalse(result.unknownCitationDetected());
    }

    @Test
    @DisplayName("无 ChatModel → NOT_CONFIGURED 且不抛异常")
    void shouldReturnNotConfigured() {
        GenerationResult result = service(Optional.empty())
                .generate("问题", contextWithRefs("S1"));

        assertEquals(GenerationStatus.NOT_CONFIGURED, result.status());
        assertEquals(RagErrorCode.MODEL_NOT_CONFIGURED, result.errorCode());
        assertNull(result.content());
    }

    @Test
    @DisplayName("韧性层抛 READ_TIMEOUT → FAILED_TIMEOUT")
    void shouldMapTimeout() {
        when(resilienceSupport.executeBlocking(any())).thenThrow(
                new ModelInvocationException(ModelErrorCategory.READ_TIMEOUT,
                        "read timeout", new RuntimeException()));

        GenerationResult result = service(Optional.of(chatModel))
                .generate("问题", contextWithRefs("S1"));

        assertEquals(GenerationStatus.FAILED_TIMEOUT, result.status());
        assertEquals(RagErrorCode.GENERATION_TIMEOUT, result.errorCode());
    }

    @Test
    @DisplayName("韧性层抛其他分类异常 → FAILED_ERROR")
    void shouldMapOtherError() {
        when(resilienceSupport.executeBlocking(any())).thenThrow(
                new ModelInvocationException(ModelErrorCategory.RATE_LIMIT_RECOVERABLE,
                        "rate limit", new RuntimeException()));

        GenerationResult result = service(Optional.of(chatModel))
                .generate("问题", contextWithRefs("S1"));

        assertEquals(GenerationStatus.FAILED_ERROR, result.status());
        assertEquals(RagErrorCode.GENERATION_FAILED, result.errorCode());
    }

    @Test
    @DisplayName("模型返回空白文本 → EMPTY_RESPONSE 受控失败")
    void shouldHandleEmptyResponse() {
        when(resilienceSupport.executeBlocking(any()))
                .thenReturn(chatResponseWith("   "));

        GenerationResult result = service(Optional.of(chatModel))
                .generate("问题", contextWithRefs("S1"));

        assertEquals(GenerationStatus.FAILED_ERROR, result.status());
        assertEquals(RagErrorCode.EMPTY_RESPONSE, result.errorCode());
    }

    @Test
    @DisplayName("回答引用上下文之外的标识 → unknownCitationDetected=true")
    void shouldDetectUnknownCitation() {
        when(resilienceSupport.executeBlocking(any()))
                .thenReturn(chatResponseWith("依据 [S9] 得出结论。"));

        GenerationResult result = service(Optional.of(chatModel))
                .generate("问题", contextWithRefs("S1", "S2"));

        assertEquals(GenerationStatus.SUCCESS, result.status());
        assertTrue(result.unknownCitationDetected());
    }

    @Test
    @DisplayName("其他运行时异常 → FAILED_ERROR 受控失败")
    void shouldHandleGenericException() {
        when(resilienceSupport.executeBlocking(any()))
                .thenThrow(new RuntimeException("boom"));

        GenerationResult result = service(Optional.of(chatModel))
                .generate("问题", contextWithRefs("S1"));

        assertEquals(GenerationStatus.FAILED_ERROR, result.status());
        assertEquals(RagErrorCode.GENERATION_FAILED, result.errorCode());
    }
}
