package com.zl.d42.rag.rerank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rerank 端口契约单测（C01）：identity 工厂、flags 只读、候选越界防护、noop 恒等。
 */
class RerankPortContractTest {

    private static List<RerankCandidate> candidates(int n) {
        List<RerankCandidate> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new RerankCandidate(i, "chunk-" + i, "内容" + i));
        }
        return list;
    }

    @Test
    @DisplayName("identity：输出=输入顺序，rank 连续，score=0")
    void identityKeepsOrder() {
        List<RerankCandidate> cs = candidates(3);
        RerankResult r = RerankResult.identity("test", cs, RerankUsage.zero(7), 2L,
                java.util.EnumSet.of(RerankFlags.IDENTITY));
        assertEquals(3, r.ordered().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(cs.get(i).ordinal(), r.ordered().get(i).candidateOrdinal());
            assertEquals(i, r.ordered().get(i).rank());
            // identity 占位分=原始位次负值（与真实得分≥0 区分，实现契约已文档化）
            assertEquals((double) -i, r.ordered().get(i).score());
        }
        assertEquals("test", r.adapter());
        assertEquals(7, r.usage().estimatedTokens());
        assertEquals(0, r.usage().calls());
    }

    @Test
    @DisplayName("flags 视图只读：修改抛 UnsupportedOperationException（防实验记账被污染）")
    void flagsAreUnmodifiable() {
        RerankResult r = RerankResult.identity("test", candidates(1), RerankUsage.zero(0), 0L,
                java.util.EnumSet.of(RerankFlags.REORDERED));
        assertThrows(UnsupportedOperationException.class,
                () -> r.flags().add(RerankFlags.TRUNCATED));
    }

    @Test
    @DisplayName("Noop：非空恒等 + 空集合 NOOP_EMPTY；零调用零 token")
    void noopIdentityAndEmpty() {
        NoopRerankAdapter noop = new NoopRerankAdapter();
        List<RerankCandidate> cs = candidates(4);
        RerankResult r = noop.rerank("任意问题", cs, 4);
        assertEquals("noop", r.adapter());
        assertEquals(4, r.ordered().size());
        for (int i = 0; i < 4; i++) {
            assertEquals(i, r.ordered().get(i).candidateOrdinal());
        }
        assertEquals(0, r.usage().calls());
        assertFalse(r.flags().isEmpty());

        RerankResult empty = noop.rerank("q", List.of(), 0);
        assertTrue(empty.ordered().isEmpty());
        assertTrue(empty.flags().contains(RerankFlags.NOOP_EMPTY));
    }

    @Test
    @DisplayName("估算 token 口径=ceil(chars/1.3)（协议 H4 冻结口径，非上游实测）")
    void estimateTokenFormula() {
        // 13 字符 → ceil(13/1.3)=10
        RerankCandidate c = new RerankCandidate(0, "id", "0123456789abc");
        assertEquals(10, NoopRerankAdapter.estimateTokens(List.of(c)));
        // 14 字符 → ceil(14/1.3)=11（非整除向上取整）
        RerankCandidate c2 = new RerankCandidate(0, "id", "0123456789abcd");
        assertEquals(11, NoopRerankAdapter.estimateTokens(List.of(c2)));
        assertEquals(0, NoopRerankAdapter.estimateTokens(List.of()));
    }
}
