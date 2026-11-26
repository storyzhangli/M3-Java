package com.zl.d43.document.sync.dto;

import lombok.Data;

/**
 * 单次 sync 重试执行结果视图（/sync/retry 与调度日志共用形态）。
 */
@Data
public class SyncRetryResultView {

    private String documentId;

    private String operation;

    private Integer version;

    /** 执行后终态（SYNCED/FAILED/PENDING-claimed 异常残留等，如实呈现）。 */
    private String syncStatus;

    /** 终态原因码。 */
    private String reasonCode;

    private Integer attemptCount;

    /** 管线执行是否未抛异常（≠ SYNCED：仍可能 CONFIRM_MISMATCH 落 FAILED）。 */
    private boolean pipelineCompleted;

    /** 安全摘要（不含堆栈与凭据，H9）。 */
    private String detail;
}
