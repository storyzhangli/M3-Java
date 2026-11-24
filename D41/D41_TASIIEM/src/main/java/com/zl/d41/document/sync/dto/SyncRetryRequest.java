package com.zl.d41.document.sync.dto;

import lombok.Data;

/**
 * 手动重试请求体（禁无约束 Map 契约）：全部字段可选，空=全部 FAILED 行。
 */
@Data
public class SyncRetryRequest {

    /** 目标文档（可空=扫描所有 FAILED 行）。 */
    private String documentId;
}
