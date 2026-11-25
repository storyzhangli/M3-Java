package com.zl.d42.rag.rerank;

/**
 * 精排调用度量（H4 指标真实：远程臂逐 call 落 usage，本地臂恒零调用零 token）。
 *
 * @param calls           本次 rerank 实际发起的远程调用次数（本地/noop=0）
 * @param totalTokens     上游返回的实测 totalTokens 累计；未发生远程调用时为 null（不伪称 0 实测）
 * @param estimatedTokens 输入侧估算 token 累计（口径=ceil(chars/1.3)，仅用于预算/截断预检，
 *                        与实测分列，绝不冒充实测值）
 * @param costCny         实测费用（元）= totalTokens x 0.8 / 1e6；无实测为 null
 * @param lastStatusCode  最近一次远程响应状态码（未调用为 null）
 * @param lastCode        最近一次上游错误码（成功/未调用为 null）
 */
public record RerankUsage(int calls, Integer totalTokens, int estimatedTokens,
                          Double costCny, Integer lastStatusCode, String lastCode) {

    /** 零调用度量（noop/local/失败未发出请求场景的诚实值）。 */
    public static RerankUsage zero(int estimatedTokens) {
        return new RerankUsage(0, null, estimatedTokens, null, null, null);
    }
}
