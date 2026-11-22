package com.zl.d39.hybrid.keyword;

import com.zl.d39.hybrid.config.HybridProperties;
import com.zl.d39.hybrid.keyword.InvertedIndex;
import com.zl.d39.hybrid.keyword.KeywordFilterCriteria;
import com.zl.d39.hybrid.keyword.KeywordMatchView;
import com.zl.d39.hybrid.keyword.KeywordTokenizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 关键词检索服务（BM25）：在内存倒排上打分排名，与向量检索完全解耦。
 *
 * <p>公式（冻结 k1/b，任务书 P1.3 / Robertson &amp; Zaragoza 2009）：</p>
 * <pre>
 * score(q,d) = Σ_t IDF(t) · tf·(k1+1) / (tf + k1·(1 − b + b·len/avgLen))
 * IDF(t)     = ln(1 + (N − df + 0.5) / (df + 0.5))
 * </pre>
 *
 * <p>确定性：查询项去重后字典序求和；结果按分数降序 → <b>同分 chunkId 升序</b>。
 * 空查询拒绝；零命中返回空列表（EMPTY 语义，非 FAILED）。
 * 过滤口径与向量路一致（documentId/title/version/strategy AND，先过滤后排名）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class KeywordSearchService {

    public record KeywordSearchOutcome(List<KeywordMatchView> matches, long elapsedNanos,
                                       int distinctQueryTerms, int candidateScanned) {
    }

    private final InvertedIndex index;
    private final KeywordTokenizer tokenizer;
    private final HybridProperties properties;

    public KeywordSearchService(@Qualifier("invertedIndex") InvertedIndex index,
                                @Qualifier("keywordTokenizer") KeywordTokenizer tokenizer,
                                @Qualifier("hybridProperties") HybridProperties properties) {
        this.index = index;
        this.tokenizer = tokenizer;
        this.properties = properties;
    }

    public KeywordSearchOutcome search(String rawQuery, int topN, KeywordFilterCriteria criteria) {
        long start = System.nanoTime();
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        List<String> terms = tokenizer.distinctSortedTerms(query);
        double k1 = properties.getBm25K1();
        double b = properties.getBm25B();
        double avgLen = index.avgDocLength();

        // 累加器按 chunkId 字典序遍历，保证浮点求和顺序跨进程一致（§3.3）
        SortedMap<String, Double> scores = new TreeMap<>();
        SortedMap<String, List<String>> matched = new TreeMap<>();
        int scanned = 0;
        for (String term : terms) {
            List<InvertedIndex.Posting> postings = index.postings(term);
            if (postings.isEmpty()) {
                continue;
            }
            double idf = index.idf(term);
            for (InvertedIndex.Posting posting : postings) {
                InvertedIndex.ChunkEntry chunk = index.chunk(posting.chunkId());
                if (chunk == null) {
                    continue; // 理论不可达：快照内 postings 与 chunks 同源构建
                }
                scanned++;
                if (!criteria.matches(chunk.filterFields())) {
                    continue;
                }
                int tf = posting.tf();
                int len = index.docLength(chunk.chunkId());
                double contribution = bm25Score(idf, tf, len, avgLen, k1, b);
                scores.merge(chunk.chunkId(), contribution, Double::sum);
                matched.computeIfAbsent(chunk.chunkId(), t -> new ArrayList<>()).add(term);
            }
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Double>, Double>comparing(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));

        List<KeywordMatchView> matches = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<String, Double> entry : ranked) {
            if (rank >= topN) {
                break;
            }
            rank++;
            InvertedIndex.ChunkEntry chunk = index.chunk(entry.getKey());
            matches.add(new KeywordMatchView(rank, chunk.chunkId(), chunk.documentId(),
                    chunk.title(), chunk.version(), chunk.strategy(), chunk.chunkIndex(),
                    chunk.content(), entry.getValue(), chunk.documentLength(),
                    List.copyOf(matched.getOrDefault(chunk.chunkId(), List.of()))));
        }
        long elapsed = System.nanoTime() - start;
        log.info("hybrid 关键词检索完成: queryLength={}, terms={}, hits={}, topN={}, ms={}",
                query.length(), terms.size(), matches.size(), topN,
                String.format(Locale.ROOT, "%.3f", elapsed / 1_000_000.0d));
        return new KeywordSearchOutcome(matches, elapsed, terms.size(), scanned);
    }

    /**
     * BM25 单词项贡献纯函数（C01 可被 SelfTest 手算夹具直测，不依赖 Spring/PG）：
     * {@code idf · tf·(k1+1) / (tf + k1·(1 − b + b·len/avgLen))}。
     */
    public static double bm25Score(double idf, int tf, int docLen, double avgLen, double k1, double b) {
        double norm = tf + k1 * (1.0d - b + b * docLen / avgLen);
        return idf * tf * (k1 + 1.0d) / norm;
    }

    /** 查询项集合（供 fusionTrace 追溯，不参与打分）。 */
    public Set<String> queryTerms(String query) {
        return new LinkedHashSet<>(tokenizer.extract(query));
    }
}
