package com.zl.d30.document.embedding.support;

import org.springframework.stereotype.Component;

/**
 * 向量结果校验器：把不可用/不合规向量挡在写入之前。
 *
 * <p>校验项：非空、维度与期望一致、无 NaN、无 Infinity（全部元素有限）。
 * 任何一项不满足都 fail-closed，抛出不可重试异常。</p>
 */
@Component
public class VectorValidator {

    /**
     * 校验单条向量。
     *
     * @param vector             待校验向量
     * @param expectedDimensions 期望维度
     */
    public void validate(float[] vector, int expectedDimensions) {
        if (vector == null) {
            throw EmbeddingServiceException.nonRetryable("Embedding 返回空向量");
        }
        if (vector.length == 0) {
            throw EmbeddingServiceException.nonRetryable("Embedding 向量长度为 0");
        }
        if (vector.length != expectedDimensions) {
            throw EmbeddingServiceException.nonRetryable(
                    "Embedding 维度不一致，期望 " + expectedDimensions + "，实际 " + vector.length);
        }
        for (int i = 0; i < vector.length; i++) {
            float v = vector[i];
            if (Float.isNaN(v)) {
                throw EmbeddingServiceException.nonRetryable("Embedding 第 " + i + " 维为 NaN");
            }
            if (Float.isInfinite(v)) {
                throw EmbeddingServiceException.nonRetryable("Embedding 第 " + i + " 维为 Infinity");
            }
        }
    }

    /**
     * 校验批量向量：数量与维度都必须一致。
     */
    public void validateBatch(float[][] vectors, int expectedDimensions) {
        if (vectors == null || vectors.length == 0) {
            throw EmbeddingServiceException.nonRetryable("Embedding 批量结果为空");
        }
        for (float[] vector : vectors) {
            validate(vector, expectedDimensions);
        }
    }
}
