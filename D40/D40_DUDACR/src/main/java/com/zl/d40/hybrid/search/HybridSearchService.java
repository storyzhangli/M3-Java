package com.zl.d40.hybrid.search;

import com.zl.d40.access.AccessPolicyService;
import com.zl.d40.access.PermissionScope;
import com.zl.d40.document.retrieval.SimilaritySearchService;
import com.zl.d40.document.retrieval.dto.SimilarityMatchView;
import com.zl.d40.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d40.document.retrieval.dto.SimilaritySearchResultView;
import com.zl.d40.hybrid.config.HybridProperties;
import com.zl.d40.hybrid.fusion.RrfFusionService;
import com.zl.d40.hybrid.keyword.KeywordFilterCriteria;
import com.zl.d40.hybrid.keyword.KeywordMatchView;
import com.zl.d40.hybrid.keyword.KeywordSearchService;
import com.zl.d40.hybrid.search.dto.HybridMatchView;
import com.zl.d40.hybrid.search.dto.HybridSearchRequest;
import com.zl.d40.hybrid.search.dto.HybridSearchResponseView;
import com.zl.d40.hybrid.search.dto.FusionTraceEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索服务：向量路 + 关键词路 + RRF 融合。
 *
 * <p><b>并行语义为串行调用</b>（任务书 P2.2，单线程确定性）：先向量后关键词。
 * 向量路只读复用既有 {@link SimilaritySearchService}（@Primary local Bean，零修改），
 * 天然继承其空白拒绝/超长拒绝/零向量拒绝/过滤 SQL 下推/三段计时/真实分数映射；
 * 关键词路走内存倒排 BM25；融合仅消费两路<b>名次</b>，不消费两路分数（量纲红线）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class HybridSearchService {

    /** 检索产物包：对外响应视图 + 融合顺序的 SimilarityMatchView 证据列表（供 RAG 复用）。 */
    public record HybridSearchBundle(HybridSearchResponseView view,
                                     List<SimilarityMatchView> orderedEvidenceMatches) {
    }

    private final SimilaritySearchService similaritySearchService;
    private final KeywordSearchService keywordSearchService;
    private final RrfFusionService rrfFusionService;
    private final HybridProperties properties;

    /** P3：权限代数可选依赖（disabled→null→criteria 无权限组件，零差异，H1）。 */
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    public HybridSearchService(
            @Qualifier("similaritySearchService") SimilaritySearchService similaritySearchService,
            @Qualifier("keywordSearchService") KeywordSearchService keywordSearchService,
            @Qualifier("rrfFusionService") RrfFusionService rrfFusionService,
            @Qualifier("hybridProperties") HybridProperties properties,
            ObjectProvider<AccessPolicyService> accessPolicyProvider) {
        this.similaritySearchService = similaritySearchService;
        this.keywordSearchService = keywordSearchService;
        this.rrfFusionService = rrfFusionService;
        this.properties = properties;
        this.accessPolicyProvider = accessPolicyProvider;
    }

    public HybridSearchBundle search(HybridSearchRequest request) {
        long totalStart = System.nanoTime();

        int topK = normalizeTopK(request.getTopK());
        double threshold = normalizeThreshold(request.getThreshold());
        int candidateN = Math.max(SimilaritySearchRequest.MIN_TOP_K,
                Math.min(SimilaritySearchRequest.MAX_TOP_K, properties.getCandidateTopN()));
        KeywordFilterCriteria criteria = new KeywordFilterCriteria(
                request.getDocumentId(), request.getTitle(), request.getVersion(), request.getStrategy(),
                permissionOrNull());
        boolean filtered = !criteria.isEmpty();

        // 1) 向量路（先执行；空白/超长/零向量拒绝语义在此 fail-closed，与 local 一致）
        SimilaritySearchResultView vector = similaritySearchService.search(
                buildVectorRequest(request, candidateN, threshold));
        List<SimilarityMatchView> vectorMatches = vector.getMatches() == null
                ? List.of() : vector.getMatches();

        // 2) 关键词路（同过滤条件、同候选数）
        KeywordSearchService.KeywordSearchOutcome keyword =
                keywordSearchService.search(request.getQuery(), candidateN, criteria);

        // 3) RRF 融合（只消费名次）
        long fusionStart = System.nanoTime();
        List<String> vectorIds = new ArrayList<>(vectorMatches.size());
        Map<String, SimilarityMatchView> vectorById = new LinkedHashMap<>();
        for (SimilarityMatchView match : vectorMatches) {
            vectorIds.add(match.getChunkId());
            vectorById.put(match.getChunkId(), match);
        }
        List<String> keywordIds = new ArrayList<>(keyword.matches().size());
        Map<String, KeywordMatchView> keywordById = new LinkedHashMap<>();
        for (KeywordMatchView match : keyword.matches()) {
            keywordIds.add(match.chunkId());
            keywordById.put(match.chunkId(), match);
        }
        List<RrfFusionService.FusedCandidate> fused =
                rrfFusionService.fuse(vectorIds, keywordIds, properties.getRrfK(), topK);
        double fusionMs = toMillis(System.nanoTime() - fusionStart);

        // 4) 组装响应 + 证据列表（向量路原对象直用；KEYWORD_ONLY 显式字段重建，similarity=null）
        List<HybridMatchView> matches = new ArrayList<>(fused.size());
        List<FusionTraceEntry> trace = new ArrayList<>(fused.size());
        List<SimilarityMatchView> evidenceMatches = new ArrayList<>(fused.size());
        int rank = 0;
        for (RrfFusionService.FusedCandidate candidate : fused) {
            rank++;
            SimilarityMatchView vectorHit = vectorById.get(candidate.chunkId());
            KeywordMatchView keywordHit = keywordById.get(candidate.chunkId());
            Double similarity = vectorHit == null ? null : vectorHit.getSimilarity();
            Double distance = vectorHit == null ? null : vectorHit.getDistance();
            String similaritySource = vectorHit != null ? "VECTOR" : "KEYWORD_ONLY";

            if (vectorHit != null) {
                evidenceMatches.add(vectorHit);
                matches.add(new HybridMatchView(rank, vectorHit.getChunkId(), vectorHit.getDocumentId(),
                        vectorHit.getTitle(), vectorHit.getVersion(), vectorHit.getStrategy(),
                        vectorHit.getChunkIndex(), vectorHit.getContent(), candidate.rrfScore(),
                        similarity, distance, similaritySource, candidate.hitSource(),
                        keywordHit == null ? List.of() : keywordHit.matchedTerms()));
            } else {
                evidenceMatches.add(toMatchView(keywordHit));
                matches.add(new HybridMatchView(rank, keywordHit.chunkId(), keywordHit.documentId(),
                        keywordHit.title(), keywordHit.version(), keywordHit.strategy(),
                        keywordHit.chunkIndex(), keywordHit.content(), candidate.rrfScore(),
                        null, null, similaritySource, candidate.hitSource(), keywordHit.matchedTerms()));
            }
            trace.add(new FusionTraceEntry(rank, candidate.chunkId(), candidate.hitSource(),
                    candidate.vectorRank(), candidate.keywordRank(), candidate.rrfScore(),
                    candidate.vectorContribution(), candidate.keywordContribution(), similarity));
        }

        HybridSearchResponseView view = new HybridSearchResponseView();
        view.setQuery(vector.getQuery());
        view.setTopK(topK);
        view.setThreshold(threshold);
        view.setRrfK(properties.getRrfK());
        view.setCandidateTopN(candidateN);
        view.setVectorSpaceId(vector.getVectorSpaceId());
        view.setFiltered(filtered);
        view.setMatches(matches);
        view.setTotal(matches.size());
        view.setKeywordMatches(keyword.matches());
        view.setFusionTrace(trace);
        view.setQueryEmbeddingMs(vector.getQueryEmbeddingMs() == null ? 0 : vector.getQueryEmbeddingMs());
        view.setVectorSearchMs(vector.getVectorSearchMs());
        view.setKeywordSearchMs(toMillis(keyword.elapsedNanos()));
        view.setFusionMs(fusionMs);
        view.setRetrievalTotalMs(toMillis(System.nanoTime() - totalStart));

        log.info("hybrid 混合检索完成: total={}, vectorHits={}, keywordHits={}, filtered={}",
                matches.size(), vectorMatches.size(), keyword.matches().size(), filtered);
        return new HybridSearchBundle(view, List.copyOf(evidenceMatches));
    }

    /** KEYWORD_ONLY 命中 → SimilarityMatchView：显式字段直传，similarity/distance 保持 null（不伪造）。 */
    private SimilarityMatchView toMatchView(KeywordMatchView keywordHit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", keywordHit.documentId());
        metadata.put("title", keywordHit.title());
        metadata.put("version", keywordHit.version());
        metadata.put("strategy", keywordHit.strategy());
        metadata.put("chunkIndex", keywordHit.chunkIndex());
        return SimilarityMatchView.from(keywordHit.chunkId(), keywordHit.content(),
                metadata, null, null);
    }

    private SimilaritySearchRequest buildVectorRequest(HybridSearchRequest request,
                                                       int candidateN, double threshold) {
        SimilaritySearchRequest vectorRequest = new SimilaritySearchRequest();
        vectorRequest.setQuery(request.getQuery());
        vectorRequest.setTopK(candidateN);
        vectorRequest.setThreshold(threshold);
        vectorRequest.setDocumentId(request.getDocumentId());
        vectorRequest.setTitle(request.getTitle());
        vectorRequest.setVersion(request.getVersion());
        vectorRequest.setStrategy(request.getStrategy());
        return vectorRequest;
    }

    /** 归一口径与 local 完全一致（topK 默认 5、钳制 1..20）。 */
    private int normalizeTopK(Integer raw) {
        if (raw == null) {
            return SimilaritySearchRequest.DEFAULT_TOP_K;
        }
        if (raw < SimilaritySearchRequest.MIN_TOP_K) {
            return SimilaritySearchRequest.MIN_TOP_K;
        }
        if (raw > SimilaritySearchRequest.MAX_TOP_K) {
            return SimilaritySearchRequest.MAX_TOP_K;
        }
        return raw;
    }

    private double normalizeThreshold(Double raw) {
        if (raw == null) {
            return 0.0d;
        }
        if (raw < 0.0d) {
            return 0.0d;
        }
        return Math.min(raw, 1.0d);
    }

    /** P3（H4 融合闭包）：关键词路权限代数注入点。enabled 时 currentScope→requireCurrent
     *  间接取上下文（无上下文抛 4030）；disabled（Bean 缺席）返回 null=不参与过滤。
     *  向量路谓词在 SimilaritySearchService 内注入——两路同源 PermissionScope，
     *  RRF 只消费两路已过滤名次，融合结果必为权限集子集（闭包，单测断言基于返回 ID 集合）。 */
    private PermissionScope permissionOrNull() {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        return policy == null ? null : policy.currentScope();
    }

    private double toMillis(long nanos) {
        return Math.round(nanos / 1_000.0d) / 1_000.0d;
    }
}
