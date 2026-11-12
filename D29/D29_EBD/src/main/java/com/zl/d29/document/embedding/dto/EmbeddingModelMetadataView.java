package com.zl.d29.document.embedding.dto;

import lombok.Data;

/**
 * 模型/向量空间元数据视图。
 */
@Data
public class EmbeddingModelMetadataView {

    private String provider;

    private String modelName;

    private String modelVersion;

    private int dimensions;

    private String vectorSpaceId;

    private int indexVersion;

    private String distanceMetric;
}
