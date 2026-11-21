package com.zl.d38.one.index.dto;

import lombok.Data;

/**
 * one_ 轨入库结果视图（字段对齐 local 轨 VectorIndexResultView，新增 vectorSpaceId）。
 */
@Data
public class OneVectorIndexResultView {

    private String documentId;
    private Integer version;
    private String strategy;
    private String status;
    private String stage;
    private int chunkCount;
    private int processed;
    private boolean idempotent;
    private String errorCode;
    private String errorMessage;
    private Integer attemptCount;
    /** one_ 轨向量空间标识。 */
    private String vectorSpaceId;
}