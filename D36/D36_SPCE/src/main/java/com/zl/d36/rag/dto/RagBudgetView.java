package com.zl.d36.rag.dto;

import lombok.Data;

/**
 * 上下文预算统计视图。
 *
 * <p>单位固定为字符（UTF-16 code unit），{@code estimationNote} 明确说明
 * 这是字符估算而非 Token，避免伪称 Token。</p>
 */
@Data
public class RagBudgetView {

    /** 预算单位：CHARACTER（UTF-16 code unit）。 */
    private String budgetUnit;

    /** 上下文预算上限（字符）。 */
    private int budgetLimit;

    /** 最终上下文实际估算长度（字符）。 */
    private int estimatedUsed;

    /** 进入上下文的 Chunk 数。 */
    private int includedChunks;

    /** 因预算被剔除的 Chunk 数。 */
    private int budgetExcludedChunks;

    /** 因无效/去重被剔除的 Chunk 数。 */
    private int invalidOrDedupChunks;

    /** 估算口径与误差说明。 */
    private String estimationNote;
}
