package com.zl.d29.document.embedding.service;

import com.zl.d29.document.embedding.config.EmbeddingProperties;
import com.zl.d29.document.embedding.model.CompatibilityDecision;
import com.zl.d29.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d29.document.embedding.support.EmbeddingServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 向量索引兼容性服务：fail-closed，防止不兼容向量混入同一索引。
 *
 * <p>判定规则：</p>
 * <ol>
 *   <li>维度不同 → {@link CompatibilityDecision#DIMENSION_MISMATCH}；</li>
 *   <li>维度相同但 vectorSpaceId 不同 → {@link CompatibilityDecision#MODEL_SPACE_MISMATCH}；</li>
 *   <li>两者都一致 → {@link CompatibilityDecision#COMPATIBLE}。</li>
 * </ol>
 *
 * <p>模型切换不允许复用旧向量：需新索引或重新 Embedding，indexVersion 递增。</p>
 */
@Slf4j
@Service
public class VectorIndexCompatibilityService {

    private EmbeddingModelMetadata currentIndex;

    public VectorIndexCompatibilityService(EmbeddingProperties properties) {
        this.currentIndex = fromProperties(properties, 1);
    }

    /**
     * 当前索引描述符。
     */
    public EmbeddingModelMetadata currentIndex() {
        return currentIndex;
    }

    /**
     * 检查候选向量空间能否写入当前索引（不抛异常，返回结论）。
     */
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

    /**
     * 断言可写入，不兼容即抛永久异常。
     */
    public void assertWritable(EmbeddingModelMetadata candidate) {
        CompatibilityDecision decision = check(candidate);
        if (!decision.isCompatible()) {
            throw EmbeddingServiceException.nonRetryable(
                    "索引兼容性校验失败：" + decision.getReason());
        }
    }

    /**
     * 模型切换：不同向量空间必须建立新索引，indexVersion 递增，旧向量不混合。
     *
     * @param newProperties 新模型配置
     * @return 切换后的新索引描述符
     */
    public EmbeddingModelMetadata switchModel(EmbeddingProperties newProperties) {
        EmbeddingModelMetadata candidate = fromProperties(newProperties, currentIndex.getIndexVersion() + 1);
        CompatibilityDecision decision = check(candidate);
        if (decision == CompatibilityDecision.COMPATIBLE) {
            // 向量空间完全一致，无需新索引，直接返回当前描述符。
            log.info("向量空间一致，无需切换索引：{}", currentIndex.getVectorSpaceId());
            return currentIndex;
        }
        log.warn("模型切换触发新索引，旧={}，新={}，新 indexVersion={}",
                currentIndex.getVectorSpaceId(), candidate.getVectorSpaceId(), candidate.getIndexVersion());
        this.currentIndex = candidate;
        return candidate;
    }

    /**
     * 由配置构造索引元数据。
     */
    public static EmbeddingModelMetadata fromProperties(EmbeddingProperties properties, int indexVersion) {
        return EmbeddingModelMetadata.builder()
                .provider(properties.getProvider())
                .modelName(properties.getModel())
                .modelVersion(properties.getModelVersion())
                .dimensions(properties.getDimensions())
                .vectorSpaceId(properties.vectorSpaceId())
                .indexVersion(indexVersion)
                .distanceMetric(properties.getDistanceMetric())
                .build();
    }
}
