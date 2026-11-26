package com.zl.d43.rag.dto;

import lombok.Data;

/**
 * RAG 各阶段耗时统计（毫秒）。
 */
@Data
public class RagTimingView {

    /** 检索耗时。 */
    private long retrievalMs;

    /**
     * 追加（additive）：精排阶段耗时（毫秒）。rerank 缺席（默认）时为 0，
     * 既有字段与语义不动（H1 零差异：评测质量投影不消费本字段）。
     */
    private long rerankMs;

    /** 上下文组装耗时。 */
    private long assemblyMs;

    /** 模型生成耗时。 */
    private long generationMs;

    /** 端到端总耗时。 */
    private long totalMs;
}
