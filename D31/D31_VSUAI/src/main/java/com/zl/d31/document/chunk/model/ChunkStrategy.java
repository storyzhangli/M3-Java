package com.zl.d31.document.chunk.model;

/**
 * Chunk 切分策略：固定长度切分、按标题/段落结构切分。
 *
 * <p>两种策略可独立执行，也可在相同输入下对比；策略之间不与
 * Embedding/VectorStore 耦合，后续可独立替换。</p>
 */
public enum ChunkStrategy {

    /** 固定长度切分（按 Chunk Size 窗口 + Overlap）。 */
    FIXED_SIZE,

    /** 按标题/段落结构切分（保持段落语义，超长再二次切分）。 */
    STRUCTURE
}
