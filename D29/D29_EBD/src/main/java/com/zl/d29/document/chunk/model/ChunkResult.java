package com.zl.d29.document.chunk.model;

import com.zl.d29.document.chunk.model.Chunk;
import com.zl.d29.document.chunk.model.CleanedDocument;
import lombok.Data;

import java.util.List;

/**
 * 一次 Chunk 处理的完整结果：清洗结果 + 两策略 Chunk + 对比统计。
 */
@Data
public class ChunkResult {

    /** 清洗结果。 */
    private CleanedDocument cleaned;

    /** 固定长度切分 Chunk。 */
    private List<Chunk> fixedChunks;

    /** 标题/段落切分 Chunk。 */
    private List<Chunk> structureChunks;

    /** 两策略对比。 */
    private ChunkComparison comparison;
}
