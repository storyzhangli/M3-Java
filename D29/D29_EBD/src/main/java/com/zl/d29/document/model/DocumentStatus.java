package com.zl.d29.document.model;

import lombok.Getter;

/**
 * 文档导入处理状态。
 *
 * <p>状态流转：{@code PENDING -> PROCESSING -> COMPLETED / FAILED}。
 * 该状态描述导入处理过程，区别于 D25 字典中的 effectiveStatus/lifecycleStatus（本轮不实现）。</p>
 */
@Getter
public enum DocumentStatus {

    /** 已登记，尚未开始读取文件。 */
    PENDING,

    /** 正在读取/解析，不允许长期停留。 */
    PROCESSING,

    /** 基础解析成功。 */
    COMPLETED,

    /** 处理失败，含错误码与安全摘要。 */
    FAILED;

    public static DocumentStatus fromName(String name) {
        for (DocumentStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知文档状态: " + name);
    }
}
