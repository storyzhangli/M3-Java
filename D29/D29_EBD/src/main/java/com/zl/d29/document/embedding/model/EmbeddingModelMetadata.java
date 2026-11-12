package com.zl.d29.document.embedding.model;

import lombok.Builder;
import lombok.Data;

/**
 * 向量空间与索引元数据：每批向量都可据此追溯到具体模型。
 *
 * <p>兼容性判断以 {@link #vectorSpaceId} 为权威标识，维度只做第一道硬校验，
 * 维度相同不等于向量空间兼容。</p>
 */
@Data
@Builder
public class EmbeddingModelMetadata {

    /** 模型服务提供方，如 local、openai。 */
    private String provider;

    /** Embedding 模型名称。 */
    private String modelName;

    /** 模型版本或部署标识。 */
    private String modelVersion;

    /** 向量维度。 */
    private int dimensions;

    /** 向量空间标识：由 provider/model/version/dimensions 组合，唯一确定语义空间。 */
    private String vectorSpaceId;

    /** 向量索引版本，模型切换或重建时递增。 */
    private int indexVersion;

    /** 相似度/距离计算方式。 */
    private DistanceMetric distanceMetric;

    /**
     * 构造稳定的向量空间标识。
     */
    public static String buildVectorSpaceId(String provider, String modelName,
                                           String modelVersion, int dimensions) {
        return provider + "|" + modelName + "|" + modelVersion + "|" + dimensions;
    }
}
