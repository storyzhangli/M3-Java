package com.zl.d30.document.embedding;

import com.zl.d30.document.embedding.support.EmbeddingServiceException;
import com.zl.d30.document.embedding.support.VectorValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 向量校验器测试：空、维度不符、NaN、Infinity 均被拒绝。
 */
class VectorValidatorTest {

    private final VectorValidator validator = new VectorValidator();

    @Test
    void validVectorPasses() {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        assertDoesNotThrow(() -> validator.validate(vector, 3));
    }

    @Test
    void nullVectorRejected() {
        EmbeddingServiceException ex = assertThrows(EmbeddingServiceException.class,
                () -> validator.validate(null, 3));
        assertFalse(ex.isRetryable());
    }

    @Test
    void emptyVectorRejected() {
        assertThrows(EmbeddingServiceException.class,
                () -> validator.validate(new float[0], 3));
    }

    @Test
    void dimensionMismatchRejected() {
        assertThrows(EmbeddingServiceException.class,
                () -> validator.validate(new float[]{1f, 2f}, 3));
    }

    @Test
    void nanRejected() {
        float[] vector = new float[]{1f, Float.NaN, 0f};
        assertThrows(EmbeddingServiceException.class, () -> validator.validate(vector, 3));
    }

    @Test
    void infinityRejected() {
        float[] vector = new float[]{1f, Float.POSITIVE_INFINITY, 0f};
        assertThrows(EmbeddingServiceException.class, () -> validator.validate(vector, 3));
    }

    @Test
    void batchRejectedWhenEmpty() {
        assertThrows(EmbeddingServiceException.class,
                () -> validator.validateBatch(new float[0][], 3));
    }

    @Test
    void validBatchPasses() {
        float[][] batch = new float[][]{{1f, 0f}, {0f, 1f}};
        assertDoesNotThrow(() -> validator.validateBatch(batch, 2));
    }
}
