package oneeval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯函数指标计算器（沿用 D36 MetricsCalculator 模式）。
 *
 * <p>口径：分母为 0 一律返回 null（报告渲染 N/A），禁止伪装成 0；
 * 所有指标可由原始记录复算。HitRate 为文档级，Recall 为 chunk 级 Macro（无 chunk 标注退化为文档级）。</p>
 */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    /** Top-K 命中中是否覆盖任一期望文档（文档级 Hit）。 */
    public static boolean docHit(Collection<String> returnedDocIds, Collection<String> expectedDocIds) {
        if (expectedDocIds == null || expectedDocIds.isEmpty()) {
            return false;
        }
        Set<String> returned = new HashSet<>(returnedDocIds);
        for (String expected : expectedDocIds) {
            if (returned.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单问 Recall：被覆盖期望 chunk 数 / 期望 chunk 总数；
     * 无 chunk 标注时按文档级（命中=1，未命中=0）；无期望标注返回 null（不可判）。
     */
    public static Double singleRecall(Collection<String> returnedLogicalIds,
                                      Collection<String> expectedChunkIds,
                                      Collection<String> returnedDocIds,
                                      Collection<String> expectedDocIds) {
        if (expectedChunkIds != null && !expectedChunkIds.isEmpty()) {
            Set<String> returned = new HashSet<>(returnedLogicalIds);
            int covered = 0;
            for (String id : expectedChunkIds) {
                if (returned.contains(id)) {
                    covered++;
                }
            }
            return (double) covered / expectedChunkIds.size();
        }
        if (expectedDocIds != null && !expectedDocIds.isEmpty()) {
            return docHit(returnedDocIds, expectedDocIds) ? 1.0 : 0.0;
        }
        return null;
    }

    /** Macro 平均：忽略 null（不可判）项；全 null 返回 null。 */
    public static Double macroAverage(List<Double> values) {
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    /** HitRate@K：命中≥1 期望文档的问题数 / 可判定问题数（有期望文档标注的问题）。 */
    public static Double hitRate(List<Boolean> docHits) {
        if (docHits.isEmpty()) {
            return null;
        }
        long hit = docHits.stream().filter(Boolean::booleanValue).count();
        return (double) hit / docHits.size();
    }

    /** Precision@K：相关来源数 / 实际来源数；来源 0 → null（N/A + EMPTY）。 */
    public static Double precision(int relevantCount, int returnedCount) {
        return returnedCount == 0 ? null : (double) relevantCount / returnedCount;
    }

    /** 噪声占比 = 1 − Precision；空检索 null。 */
    public static Double noise(int relevantCount, int returnedCount) {
        Double p = precision(relevantCount, returnedCount);
        return p == null ? null : 1.0 - p;
    }

    /** 比率通用：分母 0 → null。 */
    public static Double rate(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    /** 耗时分布：均值/P50/P95/min/max；空集合全 null。P95 用最近秩法（确定性）。 */
    public record TimingStats(Double mean, Double p50, Double p95, Double min, Double max, int count) {
    }

    public static TimingStats timing(List<Double> samples) {
        if (samples == null || samples.isEmpty()) {
            return new TimingStats(null, null, null, null, null, 0);
        }
        List<Double> sorted = new ArrayList<>(samples);
        java.util.Collections.sort(sorted);
        double sum = 0;
        for (double v : sorted) {
            sum += v;
        }
        return new TimingStats(sum / sorted.size(),
                percentile(sorted, 50), percentile(sorted, 95),
                sorted.get(0), sorted.get(sorted.size() - 1), sorted.size());
    }

    /** 最近秩百分位：rank = ceil(p/100 * n)，1 起。 */
    public static double percentile(List<Double> sorted, int p) {
        int n = sorted.size();
        int rank = (int) Math.ceil(p / 100.0 * n);
        rank = Math.max(1, Math.min(rank, n));
        return sorted.get(rank - 1);
    }

    /** 渲染：null → "N/A"；数值保留 4 位小数（确定性字符串，进基线对比键）。 */
    public static String fmt(Double v) {
        return v == null ? "N/A" : String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}
