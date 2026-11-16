package com.zl.d33.rag.dto;

import lombok.Data;

/**
 * RAG 各阶段耗时统计（毫秒）。
 */
@Data
public class RagTimingView {

    /** 检索耗时。 */
    private long retrievalMs;

    /** 上下文组装耗时。 */
    private long assemblyMs;

    /** 模型生成耗时。 */
    private long generationMs;

    /** 端到端总耗时。 */
    private long totalMs;
}
