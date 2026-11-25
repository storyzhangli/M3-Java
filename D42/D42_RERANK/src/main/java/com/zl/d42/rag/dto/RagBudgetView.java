package com.zl.d42.rag.dto;

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

    // ---------- D42 追加（additive，rerank 观测面；缺席=全 null/0，既有字段不动） ----------

    /** 精排适配器名（noop/local/dashscope/defensive）；rerank 缺席=null。 */
    private String rerankAdapter;

    /** 精排实际远程 call 数（本地臂恒 0；缺席=null）。 */
    private Integer rerankCalls;

    /** 精排实测 token 累计（上游 usage 权威值；无实测=null，不伪称 0）。 */
    private Integer rerankMeasuredTokens;

    /** 精排输入估算 token（口径=ceil(chars/1.3)，仅预算/截断预检，不冒充实测）。 */
    private Integer rerankEstimatedTokens;

    /** 精排实测费用（元）=实测 token×官方单价；无实测=null。 */
    private Double rerankCostCny;

    /** 精排标记 CSV（如 REORDERED 或 FAILED_OPEN,RETRIED）；缺席=null。 */
    private String rerankFlags;
}
