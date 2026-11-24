package com.zl.d41.hybrid.search.dto;

import java.util.List;

/**
 * 混合检索单条命中（record，显式字段）。
 *
 * <p><b>分数语义（§3.2 红线）</b>：{@code score} 为 RRF 融合分（量纲约 1/(60+rank) 起，
 * 非相似度）；{@code similarity}/{@code distance} 为向量路<b>原始余弦</b>回填值，
 * 关键词独有命中为 null 且 {@code similaritySource=KEYWORD_ONLY}，绝不伪造。</p>
 */
public record HybridMatchView(
        int rank,
        String chunkId,
        String documentId,
        String title,
        Integer version,
        String strategy,
        Integer chunkIndex,
        String content,
        double score,
        Double similarity,
        Double distance,
        String similaritySource,
        String hitSource,
        List<String> matchedTerms) {
}
