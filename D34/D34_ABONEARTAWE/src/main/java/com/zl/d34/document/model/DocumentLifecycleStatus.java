package com.zl.d34.document.model;

import lombok.Getter;

/**
 * 文档生命周期状态（生效状态），区别于导入处理状态 {@link DocumentStatus}。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>{@link #DRAFT}：已登记但尚未生效，不能作为有效证据；</li>
 *   <li>{@link #ACTIVE}：已生效，可作为有效证据；</li>
 *   <li>{@link #DEPRECATED}：已废弃，被新版替代，应在评估时筛除。</li>
 * </ul>
 *
 * <p>该状态描述“是否生效”，与描述导入过程的 PENDING/PROCESSING/COMPLETED/FAILED 相互独立。</p>
 */
@Getter
public enum DocumentLifecycleStatus {

    /** 未生效（含版本号更大但尚未发布）。 */
    DRAFT,

    /** 生效中。 */
    ACTIVE,

    /** 已废弃。 */
    DEPRECATED;

    /**
     * 按名称解析；无法识别时回退为 {@link #ACTIVE}，避免因未知值造成过度阻断，并在调用处记录。
     */
    public static DocumentLifecycleStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            return ACTIVE;
        }
        for (DocumentLifecycleStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) {
                return status;
            }
        }
        return ACTIVE;
    }

    public boolean isEffective() {
        return this == ACTIVE;
    }
}
