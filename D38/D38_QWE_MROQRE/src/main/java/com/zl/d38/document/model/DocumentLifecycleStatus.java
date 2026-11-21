package com.zl.d38.document.model;

import com.zl.d38.document.model.DocumentStatus;
import lombok.Getter;

/**
 * 文档生命周期状态（生效状态），区别于导入处理状态 {@link DocumentStatus}。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>{@link #DRAFT}：已登记但尚未生效，不能作为有效证据；</li>
 *   <li>{@link #ACTIVE}：已生效，唯一可作为有效证据的状态；</li>
 *   <li>{@link #DEPRECATED}：已废弃，被新版替代，应在评估时筛除；</li>
 *   <li>{@link #UNKNOWN}：存储状态为空或无法识别，状态不可确认，不得作为有效证据；</li>
 *   <li>{@link #NOT_FOUND}：库中无该文档行，业务上不存在，不得默认放行；</li>
 *   <li>{@link #LOOKUP_FAILED}：数据库查询等基础设施故障，与业务状态区分，不得作为有效证据。</li>
 * </ul>
 *
 * <p><b>Fail-closed 口径</b>：除 {@link #ACTIVE} 外全部为非有效状态；无法确认状态时
 * 返回 {@link #UNKNOWN} 而非 ACTIVE，避免故障时默认放行。该状态描述“是否生效”，与描述
 * 导入过程的 PENDING/PROCESSING/COMPLETED/FAILED 相互独立。</p>
 */
@Getter
public enum DocumentLifecycleStatus {

    /** 未生效（含版本号更大但尚未发布）。 */
    DRAFT,

    /** 生效中，唯一有效状态。 */
    ACTIVE,

    /** 已废弃。 */
    DEPRECATED,

    /** 状态无法确认（存储值为空/无法解析）。 */
    UNKNOWN,

    /** 文档不存在（无对应行）。 */
    NOT_FOUND,

    /** 生命周期查询失败（基础设施故障）。 */
    LOOKUP_FAILED;

    /**
     * 按名称解析；无法识别或为空时返回 {@link #UNKNOWN}（Fail-closed），
     * 不再回退 ACTIVE，调用处可据此受控拒答。
     */
    public static DocumentLifecycleStatus fromName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        for (DocumentLifecycleStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) {
                return status;
            }
        }
        return UNKNOWN;
    }

    /** 仅 ACTIVE 为有效；UNKNOWN/NOT_FOUND/LOOKUP_FAILED 等均非有效。 */
    public boolean isEffective() {
        return this == ACTIVE;
    }
}
