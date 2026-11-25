package com.zl.d42.rag.rerank;

import lombok.extern.slf4j.Slf4j;

import java.util.EnumSet;
import java.util.List;

/**
 * 恒等精排适配器：原序返回，零调用零成本。
 *
 * <p>装配条件：enabled=true 且 strategy=noop（用于开关语义自证与消融对照）。
 * 默认（enabled=false）时本 Bean 根本不装配，编排层经 ObjectProvider 缺席路径
 * 与引入前逐字节一致（H1）。</p>
 */
@Slf4j
public class NoopRerankAdapter implements RerankPort {

    @Override
    public RerankResult rerank(String query, List<RerankCandidate> candidates, int topN) {
        long start = System.nanoTime();
        EnumSet<RerankFlags> flags = candidates.isEmpty()
                ? EnumSet.of(RerankFlags.NOOP_EMPTY)
                : EnumSet.of(RerankFlags.IDENTITY);
        long ms = (System.nanoTime() - start) / 1_000_000L;
        log.debug("rerank(noop) 恒等返回: candidates={}", candidates.size());
        return RerankResult.identity("noop", candidates,
                RerankUsage.zero(estimateTokens(candidates)), ms, flags);
    }

    /** 与 KP4 冻结口径一致的估算（仅作观测，不冒充任何实测值）。 */
    static int estimateTokens(List<RerankCandidate> candidates) {
        long chars = candidates.stream().mapToLong(c -> c.contentOrEmpty().length()).sum();
        return (int) Math.ceil(chars / 1.3d);
    }
}
