package com.zl.d38.hybrid.search.dto;

import com.zl.d38.hybrid.keyword.KeywordMatchView;
import lombok.Data;

import java.util.List;

/**
 * 混合检索响应视图：对齐 local 契约字段（query/topK/threshold/vectorSpaceId/filtered/matches/total）
 * 并新增 strategy/scoreSemantic/keywordMatches/fusionTrace/rrfK/candidateTopN 与四段计时
 * （向量路三段透传 + keywordSearchMs/fusionMs）。
 */
@Data
public class HybridSearchResponseView {

    private String query;
    private int topK;
    private double threshold;

    /** 检索策略标识（hybrideval 质量投影入键）。 */
    private String strategy = "HYBRID_RRF";

    /** matches.score 语义：恒为 RRF（禁止伪装余弦，§3.2）。 */
    private String scoreSemantic = "RRF";

    private int rrfK;
    private int candidateTopN;
    private String vectorSpaceId;
    private boolean filtered;

    private List<HybridMatchView> matches;
    private int total;

    /** 关键词路独立结果（BM25 原始分与命中项，供追溯，非融合输出）。 */
    private List<KeywordMatchView> keywordMatches;

    /** 融合 Top-K 的两路名次/贡献明细。 */
    private List<FusionTraceEntry> fusionTrace;

    // 分段计时（毫秒 3 位小数；口径与 local 三段一致，新增两段）
    private double queryEmbeddingMs;
    private Double vectorSearchMs;
    private double keywordSearchMs;
    private double fusionMs;
    private double retrievalTotalMs;
}
