package com.zl.d31.document.vectorindex.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 核对请求：默认 dryRun，只出报告不执行补偿。
 */
@Data
public class ReconcileRequest {

    /** 是否仅演练（true 不执行补偿删除/重建）。 */
    @NotNull(message = "dryRun 必须显式指定")
    private Boolean dryRun = Boolean.TRUE;
}
