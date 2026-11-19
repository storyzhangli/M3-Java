package com.zl.d36.rag.generation;

import com.zl.d36.rag.context.ContextAssemblyResult;
import com.zl.d36.rag.context.EvidenceItem;
import com.zl.d36.rag.generation.GenerationResult;
import com.zl.d36.rag.model.GenerationStatus;
import com.zl.d36.rag.model.RagErrorCode;
import com.zl.d36.rag.prompt.RagPromptBuilder;
import com.zl.d36.resilience.ModelInvocationException;
import com.zl.d36.resilience.ModelErrorCategory;
import com.zl.d36.resilience.ModelResilienceSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成服务：独立负责 Prompt 构造与 ChatModel 调用。
 *
 * <p><b>职责边界</b>：只接收用户问题与 {@link ContextAssemblyResult}，不访问
 * VectorStore、Repository 或数据库。模型超时/异常/空响应均受控处理，不伪装成功。</p>
 *
 * <p>ChatModel 以 {@link Optional} 注入：mock 模式下无 ChatModel Bean，
 * 需要生成时返回 {@link GenerationStatus#NOT_CONFIGURED}，而非启动失败。</p>
 */
@Slf4j
@Service
public class GenerationService {

    /** 匹配回答中的来源标识 [S1]…，用于核验是否引用未知标识。 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    private final Optional<ChatModel> chatModel;

    private final ModelResilienceSupport resilienceSupport;

    private final RagPromptBuilder promptBuilder;

    public GenerationService(Optional<ChatModel> chatModel,
                             ModelResilienceSupport resilienceSupport,
                             RagPromptBuilder promptBuilder) {
        this.chatModel = chatModel;
        this.resilienceSupport = resilienceSupport;
        this.promptBuilder = promptBuilder;
    }

    /**
     * 执行生成。
     *
     * @param question 用户问题
     * @param context  已组装上下文（调用方应保证其中存在证据）
     * @return 生成结果（永不返回 null）
     */
    public GenerationResult generate(String question, ContextAssemblyResult context) {
        long start = System.nanoTime();
        if (chatModel == null || chatModel.isEmpty()) {
            log.warn("需要生成但当前无可用 ChatModel，受控返回 NOT_CONFIGURED");
            return GenerationResult.failure(GenerationStatus.NOT_CONFIGURED,
                    RagErrorCode.MODEL_NOT_CONFIGURED,
                    "当前未配置可用的聊天模型，无法执行生成", elapsedMs(start));
        }

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(promptBuilder.systemPrompt()),
                new UserMessage(promptBuilder.userPrompt(question, context.getContextText()))));

        try {
            ChatResponse response = resilienceSupport.executeBlocking(
                    () -> chatModel.orElseThrow().call(prompt));
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                log.warn("模型返回空响应，受控处理");
                return GenerationResult.failure(GenerationStatus.FAILED_ERROR,
                        RagErrorCode.EMPTY_RESPONSE, "模型返回空响应，已按受控失败处理",
                        elapsedMs(start));
            }
            boolean unknownCitation = containsUnknownCitation(text, context);
            if (unknownCitation) {
                log.warn("模型回答引用了上下文之外的未知来源标识");
            }
            return GenerationResult.success(text, unknownCitation, elapsedMs(start));
        } catch (ModelInvocationException ex) {
            return handleModelException(ex, start);
        } catch (RuntimeException ex) {
            log.error("生成过程发生未预期异常，异常类型: {}", ex.getClass().getSimpleName());
            return GenerationResult.failure(GenerationStatus.FAILED_ERROR,
                    RagErrorCode.GENERATION_FAILED, "模型调用失败，请稍后重试", elapsedMs(start));
        }
    }

    /**
     * 依据韧性层分类映射受控状态：超时与其他错误区分。
     */
    private GenerationResult handleModelException(ModelInvocationException ex, long start) {
        ModelErrorCategory category = ex.getCategory();
        log.error("模型调用受控失败，分类: {}", category);
        if (category == ModelErrorCategory.READ_TIMEOUT) {
            return GenerationResult.failure(GenerationStatus.FAILED_TIMEOUT,
                    RagErrorCode.GENERATION_TIMEOUT,
                    "模型调用超时，已返回受控错误而非正常回答", elapsedMs(start));
        }
        return GenerationResult.failure(GenerationStatus.FAILED_ERROR,
                RagErrorCode.GENERATION_FAILED,
                "模型调用失败，请稍后重试", elapsedMs(start));
    }

    /**
     * 检查回答中的 [Sx] 是否都在上下文来源集合内。
     */
    private boolean containsUnknownCitation(String text, ContextAssemblyResult context) {
        java.util.Set<String> validRefs = new java.util.HashSet<>();
        for (EvidenceItem item : context.getEvidence()) {
            validRefs.add(item.getSourceRef());
        }
        Matcher matcher = CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            if (!validRefs.contains(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
