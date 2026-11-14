package com.zl.d31.document.vectorindex.dto;

import lombok.Data;

/**
 * 向量索引结果视图：真实反映状态机终态与是否命中幂等。
 */
@Data
public class VectorIndexResultView {

    private String documentId;

    private Integer version;

    private String strategy;

    /** 终态：COMPLETED/PARTIAL/FAILED 等。 */
    private String status;

    /** 最后完成阶段。 */
    private String stage;

    /** 该版本 Chunk 总数。 */
    private int chunkCount;

    /** 本次实际提交到 PG 的 Chunk 数（含 Upsert 覆盖）。 */
    private int processed;

    /** 是否为重复导入命中的幂等返回。 */
    private boolean idempotent;

    private String errorCode;

    private String errorMessage;

    private int attemptCount;
}
