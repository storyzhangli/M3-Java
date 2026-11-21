package com.zl.d38.rag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * RAG 问答请求 DTO。
 *
 * <p>过滤字段仅开放已验证支持的 metadata 键（documentId/version/strategy/title），
 * 与内部检索/模型对象分离。</p>
 */
@Data
public class RagQuestionRequest {

    /** 用户问题（自然语言），空白拒绝；长度上限与 app.ai.max-message-length 对齐。 */
    @NotBlank(message = "question 不能为空")
    @Size(max = 2000, message = "question 长度不能超过 2000 字符")
    private String question;

    /** 可选 Top-K，范围 1..20；为 null 时使用默认值。 */
    @Min(value = 1, message = "topK 不能小于 1")
    @Max(value = 20, message = "topK 不能大于 20")
    private Integer topK;

    /** 可选：限定来源文档。 */
    private String documentId;

    /** 可选：限定版本。 */
    private Integer version;

    /** 可选：限定切分策略。 */
    private String strategy;

    /** 可选：限定文档标题。 */
    private String title;
}
