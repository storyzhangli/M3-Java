package com.zl.d29.document.embedding.support;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

/**
 * Embedding 故障分类器：区分临时故障（可重试）与永久故障（快速失败）。
 *
 * <p>口径与项目韧性体系一致：限流/上游 5xx/建连失败可重试；
 * 鉴权/参数/模型不存在等 4xx 永久失败，不浪费重试。</p>
 */
@Component
public class EmbeddingFailureClassifier {

    /**
     * 判断异常是否可重试。
     */
    public boolean isRetryable(Throwable throwable) {
        if (throwable instanceof TransientAiException) {
            return true;
        }
        if (throwable instanceof NonTransientAiException) {
            return false;
        }
        if (throwable instanceof EmbeddingServiceException) {
            return ((EmbeddingServiceException) throwable).isRetryable();
        }
        // 未归类异常保守按不可重试处理，避免无限/盲目重试。
        return false;
    }
}
