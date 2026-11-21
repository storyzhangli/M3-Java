package com.zl.d38.rag.model;

/**
 * 生成阶段状态。
 *
 * <ul>
 *   <li>{@link #SUCCESS}：模型正常返回非空回答；</li>
 *   <li>{@link #SKIPPED_NO_EVIDENCE}：无可用证据，按约束跳过模型调用；</li>
 *   <li>{@link #NOT_ATTEMPTED}：上游检索失败，未进入生成；</li>
 *   <li>{@link #NOT_CONFIGURED}：需要生成但当前无可用 ChatModel；</li>
 *   <li>{@link #FAILED_TIMEOUT}：模型调用超时，受控失败；</li>
 *   <li>{@link #FAILED_ERROR}：模型异常、空响应或其他受控失败。</li>
 * </ul>
 */
public enum GenerationStatus {
    SUCCESS,
    SKIPPED_NO_EVIDENCE,
    NOT_ATTEMPTED,
    NOT_CONFIGURED,
    FAILED_TIMEOUT,
    CLARIFY,
    CONFLICT,
    FAILED_ERROR
}
