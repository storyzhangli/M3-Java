package com.zl.d42.rag.rerank;

import java.util.List;

/**
 * 检索后精排端口（Rerank Port）：供应商中立的 Top-K 重排抽象。
 *
 * <p>设计对齐 {@code adapter.ChatModelPort} 端口适配器范式（D10）：本接口只引用工程内
 * 类型化契约，不泄漏 DashScope 专有类型；具体实现由 {@link NoopRerankAdapter}（恒等）、
 * {@link LocalLexicalRerankAdapter}（本地词法）与 {@link DashScopeRerankAdapter}
 * （远程 gte-rerank-v2）提供，经配置枚举 {@code app.ai.rag.rerank.strategy} 选择。</p>
 *
 * <p>关键契约（H8 fail-open）：实现方<b>永不向调用方抛出中断性异常</b>——任何内部失败
 * 必须回退为原始候选序，并以 {@link RerankFlags#FAILED_OPEN} 标记；空候选直接返回
 * {@link RerankFlags#NOOP_EMPTY}。精排是增值步骤，绝不阻断检索链路。</p>
 */
public interface RerankPort {

    /**
     * 对已按向量相似度排序的候选执行精排。
     *
     * @param query      本轮问题（非空）
     * @param candidates 原始向量序候选（按 rank 升序；空列表 → noop）
     * @param topN       调用方可接受的最大输出数（候选数与配置的 max-output 由实现方取 min）
     * @return 精排结果（有序 + 分数 + usage + 耗时 + flags；失败时为原序 + FAILED_OPEN）
     */
    RerankResult rerank(String query, List<RerankCandidate> candidates, int topN);
}
