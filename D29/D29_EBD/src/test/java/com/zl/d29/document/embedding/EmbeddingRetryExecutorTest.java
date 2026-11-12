package com.zl.d29.document.embedding;

import com.zl.d29.document.embedding.config.EmbeddingProperties;
import com.zl.d29.document.embedding.support.EmbeddingFailureClassifier;
import com.zl.d29.document.embedding.support.EmbeddingRetryExecutor;
import com.zl.d29.document.embedding.support.EmbeddingServiceException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 重试执行器测试（受控，无真实睡眠）：临时故障重试、永久故障快速失败、次数耗尽且不无限。
 */
class EmbeddingRetryExecutorTest {

    private EmbeddingRetryExecutor executor;

    private final AtomicInteger sleepCount = new AtomicInteger();

    private EmbeddingRetryExecutor newExecutor(int maxAttempts) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setMaxAttempts(maxAttempts);
        return new EmbeddingRetryExecutor(properties, new EmbeddingFailureClassifier(),
                ms -> sleepCount.incrementAndGet());
    }

    @Test
    void transientFailureRetriesThenSucceeds() {
        executor = newExecutor(3);
        StubEmbeddingModel stub = new StubEmbeddingModel(8, 2, true);
        String result = executor.execute(() -> "ok-" + stub.embed("text")[0]);
        assertEquals("ok-1.0", result);
        assertEquals(3, stub.getCallCount());
        assertEquals(2, sleepCount.get());
    }

    @Test
    void permanentFailureFailsFastWithoutRetry() {
        executor = newExecutor(3);
        StubEmbeddingModel stub = new StubEmbeddingModel(8, 1, false);
        assertThrows(EmbeddingServiceException.class, () -> executor.execute(() -> stub.embed("text")));
        assertEquals(1, stub.getCallCount());
        assertEquals(0, sleepCount.get());
    }

    @Test
    void transientFailureExhaustsAttempts() {
        executor = newExecutor(3);
        StubEmbeddingModel stub = new StubEmbeddingModel(8, 5, true);
        assertThrows(EmbeddingServiceException.class, () -> executor.execute(() -> stub.embed("text")));
        assertEquals(3, stub.getCallCount());
        assertEquals(2, sleepCount.get());
    }

    @Test
    void maxAttemptsOneNeverSleeps() {
        executor = newExecutor(1);
        StubEmbeddingModel stub = new StubEmbeddingModel(8, 3, true);
        assertThrows(EmbeddingServiceException.class, () -> executor.execute(() -> stub.embed("text")));
        assertEquals(1, stub.getCallCount());
        assertEquals(0, sleepCount.get());
    }

    @Test
    void unknownExceptionIsNotRetried() {
        executor = newExecutor(3);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> executor.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("未归类异常");
        }));
        assertEquals(1, calls.get());
    }
}
