package com.zl.d43.rag.rerank;

import com.zl.d43.rag.rerank.RerankFlags;
import com.zl.d43.rag.rerank.RerankUsage;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 精排结果：有序输出 + 逐候选分数 + usage + 耗时 + 标记（类型化 record，D10）。
 *
 * <p>ordered 按精排后 rank 升序（rank 从 0 开始）；FAILED_OPEN 回退时 ordered 即原序
 * （rank=ordinal 恒等映射）。rerankMs 为精排阶段端到端耗时（nanoTime 口径，D7）。</p>
 *
 * @param adapter 实际产出本结果的适配器名（noop/local/dashscope）
 * @param ordered 精排后条目（可能因 max-output 少于输入数）
 * @param usage   调用度量
 * @param rerankMs 精排阶段毫秒耗时
 * @param flags   结果标记集合（至少含 IDENTITY/REORDERED/NOOP_EMPTY/FAILED_OPEN 之一）
 */
public record RerankResult(String adapter,
                           List<Item> ordered,
                           RerankUsage usage,
                           long rerankMs,
                           Set<RerankFlags> flags) {

    /**
     * 单条精排输出。
     *
     * @param candidateOrdinal 候选在原向量序中的位次（0-based，映射回 SimilarityMatchView 的权威键）
     * @param rank             精排后位次（0-based）
     * @param score            精排分数（local=BM25-lite 得分；remote=relevanceScore；noop=原始位次负值占位）
     */
    public record Item(int candidateOrdinal, int rank, double score) {
    }

    /** 便捷构造：规范 flags 为不可变 EnumSet（防实验记账集合被事后篡改，C01 契约）。 */
    public RerankResult {
        flags = java.util.Collections.unmodifiableSet(flags.isEmpty()
                ? EnumSet.noneOf(RerankFlags.class) : EnumSet.copyOf(flags));
    }

    /** 原序回退结果（fail-open 与 noop 的统一出口）。 */
    public static RerankResult identity(String adapter, List<RerankCandidate> candidates,
                                        RerankUsage usage, long rerankMs, Set<RerankFlags> flags) {
        List<Item> items = new java.util.ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            items.add(new Item(candidates.get(i).ordinal(), i, -i));
        }
        return new RerankResult(adapter, List.copyOf(items), usage, rerankMs, flags);
    }
}
