package com.zl.d42.one.client;

/**
 * one_ 轨远程 Embedding 受控异常：携带 HTTP 状态与稳定错误码，消息已脱敏。
 *
 * <p>分类语义对齐 {@code app.ai.resilience} 口径：</p>
 * <ul>
 *   <li>429/5xx/连接类 IO → 可重试（客户端内部有限重试后仍失败才抛出）；</li>
 *   <li>400/401/403/404 → 不可重试（参数/鉴权/模型错误，重试无意义）；</li>
 *   <li>响应结构异常/零向量/维度不符 → 不可重试（数据契约破坏）。</li>
 * </ul>
 */
public class OneEmbeddingException extends RuntimeException {

    /** HTTP 状态码；0 表示网络层失败（未收到响应）。 */
    private final int httpStatus;

    /** 稳定错误码（如 AUTH_FAILED / MODEL_NOT_FOUND / RATE_LIMITED / REMOTE_ERROR / NETWORK / BAD_RESPONSE）。 */
    private final String errorCode;

    /** 是否可重试（仅供上层观测；客户端内部已按分类完成有限重试）。 */
    private final boolean retryable;

    public OneEmbeddingException(int httpStatus, String errorCode, boolean retryable, String sanitizedMessage) {
        super(sanitizedMessage);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public OneEmbeddingException(int httpStatus, String errorCode, boolean retryable, String sanitizedMessage, Throwable cause) {
        super(sanitizedMessage, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}