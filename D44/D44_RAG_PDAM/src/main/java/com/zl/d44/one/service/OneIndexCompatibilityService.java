package com.zl.d44.one.service;

import com.zl.d44.document.embedding.model.CompatibilityDecision;
import com.zl.d44.document.embedding.model.DistanceMetric;
import com.zl.d44.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d44.document.embedding.support.EmbeddingServiceException;
import com.zl.d44.one.config.OneEmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * one_ 轨向量索引兼容性服务：fail-closed，语义对齐 local 轨
 * {@code VectorIndexCompatibilityService}，当前索引描述符取 one_ 冻结参数。
 *
 * <p>判定规则：维度不同 → DIMENSION_MISMATCH；维度相同但 vectorSpaceId 不同
 * → MODEL_SPACE_MISMATCH（跨空间禁止复用向量）；两者一致 → COMPATIBLE。
 * one_ 距离度量冻结为 COSINE（P0 探测：向量未归一化，COSINE 对模长不敏感）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneIndexCompatibilityService {

    private final EmbeddingModelMetadata currentIndex;

    public OneIndexCompatibilityService(OneEmbeddingProperties properties) {
        this.currentIndex = EmbeddingModelMetadata.builder()
                .provider(properties.getProvider())
                .modelName(properties.getModel())
                .modelVersion(properties.getModelVersion())
                .dimensions(properties.getDimensions())
                .vectorSpaceId(properties.vectorSpaceId())
                .indexVersion(1)
                .distanceMetric(DistanceMetric.COSINE)
                .build();
    }

    /** 当前 one_ 索引描述符。 */
    public EmbeddingModelMetadata currentIndex() {
        return currentIndex;
    }

    /** 检查候选向量空间能否写入 one_ 索引（不抛异常，返回结论）。 */
    public CompatibilityDecision check(EmbeddingModelMetadata candidate) {
        if (candidate == null) {
            throw EmbeddingServiceException.nonRetryable("候选索引元数据为空");
        }
        if (candidate.getDimensions() != currentIndex.getDimensions()) {
            return CompatibilityDecision.DIMENSION_MISMATCH;
        }
        if (!candidate.getVectorSpaceId().equals(currentIndex.getVectorSpaceId())) {
            return CompatibilityDecision.MODEL_SPACE_MISMATCH;
        }
        return CompatibilityDecision.COMPATIBLE;
    }
}