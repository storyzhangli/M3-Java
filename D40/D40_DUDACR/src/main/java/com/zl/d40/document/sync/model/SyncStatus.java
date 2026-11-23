package com.zl.d40.document.sync.model;

/**
 * 操作级同步状态（KP4 冻结三态，任务书 §3.2）。
 *
 * <p>与索引任务状态机（{@code VectorIndexStatus} 6 态，管入库）正交共存：
 * 本状态机只管删除/旧版本清理的跨系统（PG↔MySQL）收敛。</p>
 *
 * <ul>
 *   <li>{@link #PENDING}：操作已标记、管线执行中或等待重试；</li>
 *   <li>{@link #SYNCED}：双端确认通过（PG count=0 ∧ MySQL 关联/任务行=0），终态；</li>
 *   <li>{@link #FAILED}：任一步失败并落库原因码；{@code attempt_count} 达上限后
 *       {@code reason_code=RETRY_EXHAUSTED}（耗尽不用第四枚举值，KP4 裁决 2）。</li>
 * </ul>
 */
public enum SyncStatus {

    /** 已标记待收敛。 */
    PENDING,

    /** 双端确认一致，已收敛。 */
    SYNCED,

    /** 失败已落库，可查询、可重试。 */
    FAILED;

    /** 安全解析；非法/空返回 null（调用方 fail-closed 处理，不默认放行）。 */
    public static SyncStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (SyncStatus status : values()) {
            if (status.name().equals(name.trim())) {
                return status;
            }
        }
        return null;
    }
}
