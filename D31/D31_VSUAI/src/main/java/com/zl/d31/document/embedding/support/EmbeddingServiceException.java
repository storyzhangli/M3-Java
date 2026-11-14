package com.zl.d31.document.embedding.support;

import lombok.Getter;

/**
 * Embedding 调用统一异常：携带是否可重试标识，供分类器与重试执行器决策。
 *
 * <p>用于建模超时、限流、鉴权失败、上游不可用、空响应、维度异常、非法数值等场景。</p>
 */
@Getter
public class EmbeddingServiceException extends RuntimeException {

    /** 是否属于可重试的临时故障。 */
    private final boolean retryable;

    public EmbeddingServiceException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public EmbeddingServiceException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** 临时故障：超时、限流、上游 5xx。 */
    public static EmbeddingServiceException recoverable(String message) {
        return new EmbeddingServiceException(message, true);
    }

    /** 永久故障：鉴权失败、非法请求、模型不存在。 */
    public static EmbeddingServiceException nonRetryable(String message) {
        return new EmbeddingServiceException(message, false);
    }
}
