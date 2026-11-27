package com.zl.d44.hybrid.search.dto;

/**
 * RRF 融合追溯条目（record）：每个进入 Top-K 的 chunk 的两路名次与贡献分，
 * 供 C08/C09/C13 案例逐条溯源（禁 Map）。
 */
public record FusionTraceEntry(
        int finalRank,
        String chunkId,
        String hitSource,
        int vectorRank,
        int keywordRank,
        double rrfScore,
        double vectorContribution,
        double keywordContribution,
        Double vectorOriginalSimilarity) {
}
