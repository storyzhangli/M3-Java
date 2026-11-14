package com.zl.d31.document.embedding.dto;

import lombok.Data;

import java.util.List;

/**
 * 向量记录视图：不直接下发完整大向量，仅给少量预览值，避免响应体过大。
 */
@Data
public class EmbeddingRecordView {

    private String documentId;

    private String title;

    private Integer version;

    private String strategy;

    private int chunkIndex;

    private String modelName;

    private int dimensions;

    private String vectorSpaceId;

    /** 前若干维向量预览，仅用于取证，不代表完整向量。 */
    private List<Float> vectorPreview;

    /** 向量 L2 模长，归一化后应接近 1。 */
    private double l2Norm;
}
