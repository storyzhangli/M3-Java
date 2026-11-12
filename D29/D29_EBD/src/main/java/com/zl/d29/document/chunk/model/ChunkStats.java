package com.zl.d29.document.chunk.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单策略切分统计：数量、长度极值与均值、长度分布、空块、重叠、覆盖与定位能力。
 *
 * <p>所有数值由真实运行的 Chunk 列表现算，不使用主观估计。覆盖统计基于
 * 规范全文逐位置标记，校验是否存在文本丢失（未覆盖）或重复（多重覆盖）。
 * 句中截断代理计数直接基于规范全文的边界字符判断。</p>
 */
@Data
public class ChunkStats {

    private ChunkStrategy strategy;
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
    private Map<String, Integer> lengthDistribution = new LinkedHashMap<>();

    /** 长度分布桶边界（UTF-16 长度）。 */
    private static final int[] BUCKET_EDGES = {0, 100, 250, 500, 1000, 2000, Integer.MAX_VALUE};

    public static ChunkStats of(ChunkStrategy strategy, List<Chunk> chunks, String fullText) {
        int fullLength = fullText.length();
        ChunkStats stats = new ChunkStats();
        stats.strategy = strategy;
        stats.chunkCount = chunks.size();

        for (int i = 0; i < BUCKET_EDGES.length - 1; i++) {
            stats.lengthDistribution.put(bucketLabel(i), 0);
        }

        if (chunks.isEmpty()) {
            stats.minLength = 0;
            stats.maxLength = 0;
            stats.averageLength = 0;
            stats.uncoveredChars = fullLength;
            return stats;
        }

        int min = Integer.MAX_VALUE;
        int max = 0;
        long sum = 0;
        long overlap = 0;
        int[] coverage = new int[fullLength];

        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            int len = chunk.getLength();
            min = Math.min(min, len);
            max = Math.max(max, len);
            sum += len;
            if (len == 0) {
                stats.emptyChunkCount++;
            }
            if (chunk.isCrossPage()) {
                stats.crossPageChunkCount++;
            }
            if (chunk.hasPageLocation()) {
                stats.chunksWithPageLocation++;
            }
            if (chunk.hasParagraphLocation()) {
                stats.chunksWithParagraphLocation++;
            }
            stats.midWordBoundaryCount += countMidWord(chunk, fullText, fullLength);
            stats.lengthDistribution.merge(bucketOf(len), 1, Integer::sum);

            for (int p = chunk.getGlobalStart(); p < chunk.getGlobalEnd() && p < fullLength; p++) {
                coverage[p]++;
            }
            if (i > 0) {
                Chunk previous = chunks.get(i - 1);
                overlap += Math.max(0, previous.getGlobalEnd() - chunk.getGlobalStart());
            }
        }

        int uncovered = 0;
        int duplicated = 0;
        for (int mark : coverage) {
            if (mark == 0) {
                uncovered++;
            } else if (mark > 1) {
                duplicated++;
            }
        }

        stats.minLength = min;
        stats.maxLength = max;
        stats.averageLength = (double) sum / chunks.size();
        stats.totalOverlapChars = overlap;
        stats.uncoveredChars = uncovered;
        stats.duplicatedChars = duplicated;
        return stats;
    }

    private static int countMidWord(Chunk chunk, String fullText, int fullLength) {
        int count = 0;
        int start = chunk.getGlobalStart();
        int end = chunk.getGlobalEnd();
        if (start > 0
                && Character.isLetterOrDigit(fullText.charAt(start - 1))
                && Character.isLetterOrDigit(fullText.charAt(start))) {
            count++;
        }
        if (end < fullLength
                && Character.isLetterOrDigit(fullText.charAt(end - 1))
                && Character.isLetterOrDigit(fullText.charAt(end))) {
            count++;
        }
        return count;
    }

    private static String bucketOf(int length) {
        for (int i = 0; i < BUCKET_EDGES.length - 1; i++) {
            if (length >= BUCKET_EDGES[i] && length < BUCKET_EDGES[i + 1]) {
                return bucketLabel(i);
            }
        }
        return bucketLabel(BUCKET_EDGES.length - 2);
    }

    private static String bucketLabel(int i) {
        int upper = BUCKET_EDGES[i + 1];
        return BUCKET_EDGES[i] + "-" + (upper == Integer.MAX_VALUE ? "∞" : upper - 1);
    }
}
