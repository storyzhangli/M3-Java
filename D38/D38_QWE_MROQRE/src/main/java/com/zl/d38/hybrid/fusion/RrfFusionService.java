package com.zl.d38.hybrid.fusion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion，倒数排名融合）纯函数服务。
 *
 * <p>公式（Cormack et al., SIGIR 2009）：{@code rrfScore(d) = Σ_route 1/(k + rank_route(d))}，
 * rank 从 1 起；缺席路不贡献。冻结 k=60。</p>
 *
 * <p>确定性：累加顺序固定为「向量路 → 关键词路」；输出按 rrfScore 降序 →
 * <b>同分 chunkId 升序</b>；不读取任何分数量纲（只用名次），因此对两路分数的
 * 不可比性天然免疫（任务书 §3.2）。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class RrfFusionService {

    /** 融合输出条目：两路名次、两路贡献与合计（均显式字段，禁 Map）。 */
    public record FusedCandidate(String chunkId, double rrfScore,
                                 int vectorRank, int keywordRank,
                                 double vectorContribution, double keywordContribution) {

        public String hitSource() {
            if (vectorRank > 0 && keywordRank > 0) {
                return "BOTH";
            }
            return vectorRank > 0 ? "VECTOR_ONLY" : "KEYWORD_ONLY";
        }
    }

    public List<FusedCandidate> fuse(List<String> vectorRankedIds, List<String> keywordRankedIds,
                                     int rrfK, int topK) {
        Map<String, double[]> acc = new LinkedHashMap<>();
        accumulate(acc, vectorRankedIds, rrfK, 0);
        accumulate(acc, keywordRankedIds, rrfK, 1);

        List<FusedCandidate> fused = new ArrayList<>(acc.size());
        for (Map.Entry<String, double[]> entry : acc.entrySet()) {
            double[] v = entry.getValue();
            fused.add(new FusedCandidate(entry.getKey(), v[0] + v[1],
                    (int) v[2], (int) v[3], v[0], v[1]));
        }
        fused.sort(Comparator.comparingDouble(FusedCandidate::rrfScore).reversed()
                .thenComparing(FusedCandidate::chunkId));
        if (fused.size() > topK) {
            return List.copyOf(fused.subList(0, topK));
        }
        return List.copyOf(fused);
    }

    /**
     * 单路累加：slot[0]=向量贡献, slot[1]=关键词贡献, slot[2]=向量名次, slot[3]=关键词名次。
     * 固定路内顺序遍历，浮点加法按「向量贡献 + 关键词贡献」两步完成，无第三路歧义。
     */
    private void accumulate(Map<String, double[]> acc, List<String> rankedIds, int rrfK, int routeSlot) {
        if (rankedIds == null) {
            return;
        }
        for (int i = 0; i < rankedIds.size(); i++) {
            String chunkId = rankedIds.get(i);
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            int rank = i + 1;
            double contribution = 1.0d / (rrfK + rank);
            double[] slot = acc.computeIfAbsent(chunkId, k -> new double[4]);
            slot[routeSlot] = contribution;
            slot[2 + routeSlot] = rank;
        }
    }
}
