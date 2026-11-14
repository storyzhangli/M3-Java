package com.zl.d31.document.chunk.model;

import lombok.Data;

import java.util.List;

/**
 * 两种切分策略对比：同一份清洗文本，分别统计固定长度与结构切分。
 *
 * <p>相同输入保证公平，数据全部来自真实运行；适用场景与局限由人工
 * 抽查与统计共同支撑，不引入大模型评分。</p>
 */
@Data
public class ChunkComparison {

    /** 全部页面清洗前总长度（UTF-16）。 */
    private int originalLength;

    /** 清洗后规范全文长度（UTF-16）。 */
    private int cleanedLength;

    /** 固定长度切分统计。 */
    private ChunkStats fixedSizeStats;

    /** 标题/段落结构切分统计。 */
    private ChunkStats structureStats;

    public static ChunkComparison of(int originalLength, CleanedDocument document,
                                     List<Chunk> fixedChunks, List<Chunk> structureChunks) {
        ChunkComparison comparison = new ChunkComparison();
        comparison.originalLength = originalLength;
        comparison.cleanedLength = document.getCleanedLength();
        comparison.fixedSizeStats = ChunkStats.of(ChunkStrategy.FIXED_SIZE, fixedChunks, document.getJoinedText());
        comparison.structureStats = ChunkStats.of(ChunkStrategy.STRUCTURE, structureChunks, document.getJoinedText());
        return comparison;
    }
}
