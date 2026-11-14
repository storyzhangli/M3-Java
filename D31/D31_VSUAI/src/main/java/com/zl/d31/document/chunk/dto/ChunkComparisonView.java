package com.zl.d31.document.chunk.dto;

import lombok.Data;

/**
 * 两策略切分对比视图。
 */
@Data
public class ChunkComparisonView {

    private int originalLength;
    private int cleanedLength;
    private ChunkStatsView fixedSizeStats;
    private ChunkStatsView structureStats;
}
