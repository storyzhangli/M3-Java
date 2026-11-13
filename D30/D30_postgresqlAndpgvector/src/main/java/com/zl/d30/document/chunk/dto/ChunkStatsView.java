package com.zl.d30.document.chunk.dto;

import lombok.Data;

import java.util.Map;

/**
 * 单策略切分统计视图。
 */
@Data
public class ChunkStatsView {

    private String strategy;
    private int chunkCount;
    private int minLength;
    private int maxLength;
    private double averageLength;
    private int emptyChunkCount;
    private long totalOverlapChars;
    private int uncoveredChars;
    private int duplicatedChars;
    private int crossPageChunkCount;
    private int chunksWithPageLocation;
    private int chunksWithParagraphLocation;
    private int midWordBoundaryCount;
    private Map<String, Integer> lengthDistribution;
}
