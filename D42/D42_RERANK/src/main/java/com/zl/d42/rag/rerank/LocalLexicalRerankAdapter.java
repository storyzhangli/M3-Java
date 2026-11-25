package com.zl.d42.rag.rerank;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 本地词法精排适配器：零成本、零外网、确定性（A1 臂，任务书 P1.3）。
 *
 * <p><b>算法口径（D42-01 冻结，同输入必同输出、可离线复算）</b>：以候选集合为微型语料做
 * BM25 打分——IDF 采用 Robertson &amp; Zaragoza (2009) 标准式
 * {@code ln(1 + (N - df + 0.5)/(df + 0.5))}（与 hybrid {@code InvertedIndex.idf} 同式，
 * 但<b>独立内联分词、不依赖 hybrid Bean</b>，hybrid 默认关闭时本类可独立装配，D1/H3）；
 * k1=1.2、b=0.75（与 hybrid 同参，常量内联）。</p>
 *
 * <p><b>内联分词</b>：小写归一；连续 ASCII [a-z0-9]+ 为整词；连续非 ASCII（中文等）
 * 产生相邻字符 bigram（长度 1 的段取单字符）；空白与标点为分隔符。</p>
 *
 * <p>排序：score 降序，同分按原向量序（ordinal 升序）稳定决胜。fail-open：任何
 * 运行时异常回退原序 + {@link RerankFlags#FAILED_OPEN}。</p>
 */
@Slf4j
public class LocalLexicalRerankAdapter implements RerankPort {

    private static final double K1 = 1.2d;
    private static final double B = 0.75d;

    private final RerankProperties properties;

    public LocalLexicalRerankAdapter(RerankProperties properties) {
        this.properties = properties;
    }

    @Override
    public RerankResult rerank(String query, List<RerankCandidate> candidates, int topN) {
        long start = System.nanoTime();
        try {
            if (candidates.isEmpty()) {
                return RerankResult.identity("local", candidates, RerankUsage.zero(0),
                        elapsedMs(start), EnumSet.of(RerankFlags.NOOP_EMPTY));
            }

            // 1) 候选 token 化（确定性）
            List<List<String>> docTokens = new ArrayList<>(candidates.size());
            Map<String, Integer> df = new HashMap<>();
            long totalLen = 0;
            for (RerankCandidate c : candidates) {
                List<String> toks = tokenize(c.contentOrEmpty());
                docTokens.add(toks);
                totalLen += toks.size();
                Set<String> distinct = new LinkedHashSet<>(toks);
                for (String t : distinct) {
                    df.merge(t, 1, Integer::sum);
                }
            }
            double avgdl = totalLen / (double) candidates.size();

            // 2) 查询去重项 → 每候选 BM25 分
            List<String> queryTerms = new ArrayList<>(new LinkedHashSet<>(
                    tokenize(query == null ? "" : query)));
            double[] scores = new double[candidates.size()];
            for (int d = 0; d < candidates.size(); d++) {
                scores[d] = bm25(queryTerms, docTokens.get(d), df, candidates.size(), avgdl);
            }

            // 3) 稳定排序：score 降序，同分 ordinal 升序
            List<RerankResult.Item> ordered = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                ordered.add(new RerankResult.Item(candidates.get(i).ordinal(), 0, scores[i]));
            }
            ordered.sort(Comparator.comparingDouble(RerankResult.Item::score).reversed()
                    .thenComparingInt(RerankResult.Item::candidateOrdinal));

            EnumSet<RerankFlags> flags = EnumSet.noneOf(RerankFlags.class);
            boolean changed = false;
            for (int rank = 0; rank < ordered.size(); rank++) {
                if (ordered.get(rank).candidateOrdinal() != candidates.get(rank).ordinal()) {
                    changed = true;
                }
            }
            flags.add(changed ? RerankFlags.REORDERED : RerankFlags.IDENTITY);

            // 4) max-output 截断（A3 臂配置；0=不截断）
            int limit = topN <= 0 ? ordered.size() : topN;
            int maxOutput = properties.getMaxOutput();
            if (maxOutput > 0 && maxOutput < limit) {
                limit = maxOutput;
                flags.add(RerankFlags.OUTPUT_TRUNCATED);
            }
            List<RerankResult.Item> truncated = new ArrayList<>(limit);
            for (int rank = 0; rank < limit; rank++) {
                RerankResult.Item src = ordered.get(rank);
                truncated.add(new RerankResult.Item(src.candidateOrdinal(), rank, src.score()));
            }

            return new RerankResult("local", List.copyOf(truncated),
                    RerankUsage.zero(NoopRerankAdapter.estimateTokens(candidates)),
                    elapsedMs(start), flags);
        } catch (RuntimeException ex) {
            // 本地确定性算法理论上不应失败；防御性 fail-open（H8），绝不阻断检索。
            log.error("rerank(local) 意外异常，回退原序，异常类型: {}", ex.getClass().getSimpleName());
            return RerankResult.identity("local", candidates, RerankUsage.zero(0),
                    elapsedMs(start), EnumSet.of(RerankFlags.FAILED_OPEN));
        }
    }

    /** BM25-lite 打分（微型语料=本轮候选集；avgdl<=0 防御分支见方法内注释）。 */
    private double bm25(List<String> queryTerms, List<String> doc, Map<String, Integer> df,
                        int totalDocs, double avgdl) {
        if (queryTerms.isEmpty() || doc.isEmpty()) {
            return 0.0d;
        }
        Map<String, Integer> tf = new TreeMap<>();
        for (String t : doc) {
            tf.merge(t, 1, Integer::sum);
        }
        double norm = avgdl <= 0.0d ? K1 : K1 * (1.0d - B + B * doc.size() / avgdl);
        double score = 0.0d;
        for (String term : queryTerms) {
            int f = tf.getOrDefault(term, 0);
            if (f == 0) {
                continue;
            }
            double idf = Math.log(1.0d + (totalDocs - df.getOrDefault(term, 0) + 0.5d)
                    / (df.getOrDefault(term, 0) + 0.5d));
            score += idf * f * (K1 + 1.0d) / (f + norm);
        }
        return score;
    }

    /**
     * 内联确定性分词：小写归一；[a-z0-9]+ 整词；非 ASCII 连续段产生相邻 bigram
     * （段长 1 时取单字符）；空白与标点分隔。
     */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        List<Character> nonAscii = new ArrayList<>();
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') {
                flushNonAscii(nonAscii, out);
                ascii.append(ch);
            } else if (ch > 0x7F && !Character.isWhitespace(ch)
                    && !isPunctuation(ch)) {
                flushAscii(ascii, out);
                nonAscii.add(ch);
            } else {
                flushAscii(ascii, out);
                flushNonAscii(nonAscii, out);
            }
        }
        flushAscii(ascii, out);
        flushNonAscii(nonAscii, out);
        return out;
    }

    private static void flushAscii(StringBuilder ascii, List<String> out) {
        if (ascii.length() > 0) {
            out.add(ascii.toString());
            ascii.setLength(0);
        }
    }

    private static void flushNonAscii(List<Character> run, List<String> out) {
        if (run.size() == 1) {
            out.add(String.valueOf(run.get(0)));
        } else {
            for (int i = 0; i + 1 < run.size(); i++) {
                out.add("" + run.get(i) + run.get(i + 1));
            }
        }
        run.clear();
    }

    /** 标点判定用 Unicode 通用类别，保证对中文标点一致切分（确定性，无区域设置依赖）。 */
    private static boolean isPunctuation(char ch) {
        int type = Character.getType(ch);
        return type == Character.DASH_PUNCTUATION || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION || type == Character.CONNECTOR_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.MATH_SYMBOL
                || type == Character.CURRENCY_SYMBOL;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
