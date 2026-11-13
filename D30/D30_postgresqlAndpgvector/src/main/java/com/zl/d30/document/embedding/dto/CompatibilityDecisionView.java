package com.zl.d30.document.embedding.dto;

import lombok.Data;

/**
 * 兼容性判定视图：返回结论、是否可写入与原因。
 */
@Data
public class CompatibilityDecisionView {

    private String decision;

    private boolean compatible;

    private String reason;

    private EmbeddingModelMetadataView currentIndex;

    private EmbeddingModelMetadataView candidate;
}
