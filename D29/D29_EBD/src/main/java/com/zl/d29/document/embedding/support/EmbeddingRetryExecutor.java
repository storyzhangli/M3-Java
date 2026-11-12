package com.zl.d29.document.embedding.support;

import com.zl.d29.document.embedding.config.EmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.LongConsumer;

/**
 * 有限重试执行器：临时故障按指数退避重试，永久故障快速失败。
 *
 * <p>重试次数有硬上限（{@code maxAttempts}，含首次），不会无限循环；
 * 退避时长按倍率指数增长并受 {@code maxBackoffMs} 限制。等待动作可注入，
 * 便于单元测试用无等待实现验证重试逻辑。</p>
 */
@Slf4j
@Component
public class EmbeddingRetryExecutor {

    private final int maxAttempts;

    private final long initialBackoffMs;

    private final double multiplier;

    private final long maxBackoffMs;

    private final EmbeddingFailureClassifier classifier;

    /** 退避等待动作，默认真实睡眠；测试可替换为计数/空操作。 */
    private final LongConsumer sleeper;

    @org.springframework.beans.factory.annotation.Autowired
    public EmbeddingRetryExecutor(EmbeddingProperties properties,
                                  EmbeddingFailureClassifier classifier) {
        this.maxAttempts = properties.getMaxAttempts();
        this.initialBackoffMs = properties.getInitialBackoffMs();
        this.multiplier = properties.getBackoffMultiplier();
        this.maxBackoffMs = properties.getMaxBackoffMs();
        this.classifier = classifier;
        this.sleeper = this::sleep;
    }

    /**
     * 测试/定制构造器：可注入等待动作。
     */
    public EmbeddingRetryExecutor(EmbeddingProperties properties,
                                  EmbeddingFailureClassifier classifier,
                                  LongConsumer sleeper) {
        this.maxAttempts = properties.getMaxAttempts();
        this.initialBackoffMs = properties.getInitialBackoffMs();
        this.multiplier = properties.getBackoffMultiplier();
        this.maxBackoffMs = properties.getMaxBackoffMs();
        this.classifier = classifier;
        this.sleeper = sleeper;
    }

    /**
     * 执行带重试的动作。
     *
     * @param action 实际调用
     * @return 成功结果
     * @throws RuntimeException 永久故障立即抛出；临时故障在次数耗尽后抛出最后一次异常
     */
    public <T> T execute(java.util.function.Supplier<T> action) {
        long backoff = initialBackoffMs;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException ex) {
                last = ex;
                boolean retryable = classifier.isRetryable(ex);
                boolean hasNext = attempt < maxAttempts;
                if (!retryable || !hasNext) {
                    log.warn("Embedding 调用结束，attempt={}，retryable={}，类型={}",
                            attempt, retryable, ex.getClass().getSimpleName());
                    throw ex;
                }
                log.warn("Embedding 临时故障，第 {} 次尝试失败，{}ms 后重试，类型={}",
                        attempt, backoff, ex.getClass().getSimpleName());
                sleeper.accept(backoff);
                backoff = Math.min(maxBackoffMs, Math.round(backoff * multiplier));
            }
        }
        throw last;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw EmbeddingServiceException.nonRetryable("Embedding 重试等待被中断");
        }
    }
}
