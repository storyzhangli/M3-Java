package com.zl.d33.document.embedding.dto;

import com.zl.d33.document.embedding.dto.EmbeddingModelMetadataView;
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
