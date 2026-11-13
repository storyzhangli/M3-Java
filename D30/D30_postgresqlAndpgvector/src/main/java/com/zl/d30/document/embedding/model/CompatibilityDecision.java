package com.zl.d30.document.embedding.model;

import lombok.Getter;

/**
 * 索引兼容性判定结果。
 *
 * <p>三种结论：兼容、维度不匹配、模型/向量空间不匹配。
 * 同维度但不同模型一律走 {@link #MODEL_SPACE_MISMATCH}，不得默认兼容。</p>
 */
@Getter
public enum CompatibilityDecision {

    /** 维度与向量空间标识均一致，可写入同一索引。 */
    COMPATIBLE(true, "维度与向量空间一致，可写入同一索引"),

    /** 维度不同，禁止写入同一索引。 */
    DIMENSION_MISMATCH(false, "向量维度不一致，禁止写入同一索引"),

    /** 维度相同但模型/向量空间不同，默认隔离，禁止混合。 */
    MODEL_SPACE_MISMATCH(false, "维度相同但向量空间不同，禁止混合检索");

    private final boolean compatible;

    private final String reason;

    CompatibilityDecision(boolean compatible, String reason) {
        this.compatible = compatible;
        this.reason = reason;
    }
}
