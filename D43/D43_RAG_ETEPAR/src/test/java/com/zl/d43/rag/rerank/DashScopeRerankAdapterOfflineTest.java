package com.zl.d43.rag.rerank;

import com.zl.d43.resilience.ModelExceptionClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 远程适配器离线行为单测（C01/E1/E4/E5）：只验证<b>不发网络请求</b>的门禁分支——
 * 缺 Key、OVERSIZED 预检、三重预算帽、空集合。真实远程调用行为由 P3 A2 实验臂
 * 在受控授权下实证（协议 rerank-protocol.json），不属单测职责。
 */
class DashScopeRerankAdapterOfflineTest {

    private static RerankProperties props(String apiKey, int requestTokenLimit, int budgetMaxCalls) {
        RerankProperties p = new RerankProperties();
        p.getRemote().setApiKey(apiKey);
        p.getRemote().setRequestTokenLimit(requestTokenLimit);
        p.getRemote().setBudgetMaxCalls(budgetMaxCalls);
        return p;
    }

    private static List<RerankCandidate> cands(int n) {
        List<RerankCandidate> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new RerankCandidate(i, "chunk-" + i, "内容" + i + "ABC"));
        }
        return list;
    }

    private static DashScopeRerankAdapter adapter(RerankProperties p) {
        return new DashScopeRerankAdapter(p, new RerankExceptionClassifier(
                new ModelExceptionClassifier()));
    }

    @Test
    @DisplayName("E1：缺 Key → FAILED_OPEN 恒等回退，零 call 零费用（不外发、不伪称 token）")
    void missingKeyFailsOpenZeroCall() {
        RerankResult r = adapter(props("", 30000, 400)).rerank("q", cands(3), 3);
        assertTrue(r.flags().contains(RerankFlags.FAILED_OPEN));
        assertEquals(0, r.usage().calls());
        assertNull(r.usage().totalTokens(), "无实测不得伪称 0（H4）");
        assertNull(r.usage().costCny());
        assertTrue(r.usage().estimatedTokens() > 0, "估算 token 仍需记账供预算");
        assertEquals(3, r.ordered().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, r.ordered().get(i).candidateOrdinal());
        }
    }

    @Test
    @DisplayName("E4：估算超请求上限 → OVERSIZED 预检回退，零 call（不发不计数）")
    void oversizedPrecheck() {
        RerankResult r = adapter(props("fake-key", 1, 400)).rerank("q", cands(3), 3);
        assertTrue(r.flags().contains(RerankFlags.OVERSIZED));
        assertEquals(0, r.usage().calls());
        assertEquals(3, r.ordered().size());
    }

    @Test
    @DisplayName("E5：预算帽 maxCalls=0 → BUDGET_STOP 停发，零 call")
    void budgetStop() {
        RerankResult r = adapter(props("fake-key", 30000, 0)).rerank("q", cands(2), 2);
        assertTrue(r.flags().contains(RerankFlags.BUDGET_STOP));
        assertEquals(0, r.usage().calls());
    }

    @Test
    @DisplayName("空候选：NOOP_EMPTY，即使 Key 合法也不触发任何逻辑")
    void emptyCandidates() {
        RerankResult r = adapter(props("fake-key", 30000, 400)).rerank("q", List.of(), 5);
        assertTrue(r.ordered().isEmpty());
        assertTrue(r.flags().contains(RerankFlags.NOOP_EMPTY));
        assertEquals(0, r.usage().calls());
    }
}
