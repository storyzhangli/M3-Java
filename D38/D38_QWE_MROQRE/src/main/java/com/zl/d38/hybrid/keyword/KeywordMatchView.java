package com.zl.d38.hybrid.keyword;

import java.util.List;

/**
 * 关键词路单条命中视图（record，显式字段，禁 Map）。
 *
 * <p>分数语义：{@code bm25Score} 为 BM25 原始分（无 0..1 上界，非相似度），
 * 禁止与余弦 similarity 混用或换算（任务书 §3.2 红线）。</p>
 */
public record KeywordMatchView(
        int rank,
        String chunkId,
        String documentId,
        String title,
        Integer version,
        String strategy,
        Integer chunkIndex,
        String content,
        double bm25Score,
        int documentLength,
        List<String> matchedTerms) {
}
