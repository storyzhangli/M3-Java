package com.zl.d42.document.sync.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 一致性核查请求体（禁无约束 Map 契约）。
 *
 * <p>dryRun 缺省 true（H4：对账默认只出报告）；documentPrefix 可选——
 * 仅当显式给出时把 driftSummary 的分母/修复目标限定到该前缀（夹具域隔离，H6）。</p>
 */
@Data
public class ConsistencyCheckRequest {

    /** 是否仅演练（默认 true；false=执行补偿，修复全走管线）。 */
    private Boolean dryRun = Boolean.TRUE;

    /** 文档前缀过滤（如 d42-）；空=全局视图（LEGACY 行如实列出但不纳入归零断言）。 */
    @Size(max = 64, message = "documentPrefix 超长")
    @Pattern(regexp = "^$|^[A-Za-z0-9_.-]+$", message = "documentPrefix 仅允许字母数字与_.-")
    private String documentPrefix = "";
}
