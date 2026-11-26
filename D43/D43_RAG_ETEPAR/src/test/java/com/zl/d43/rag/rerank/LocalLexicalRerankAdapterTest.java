package com.zl.d43.rag.rerank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地词法精排单测（A1 臂，C01）：分词口径、BM25 手算对照、确定性、稳定决胜、截断。
 */
class LocalLexicalRerankAdapterTest {

    private static RerankProperties props(int maxOutput) {
        RerankProperties p = new RerankProperties();
        p.setMaxOutput(maxOutput);
        return p;
    }

    private static List<RerankCandidate> cands(String... contents) {
        List<RerankCandidate> list = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            list.add(new RerankCandidate(i, "chunk-" + i, contents[i]));
        }
        return list;
    }

    @Test
    @DisplayName("内联分词：ASCII 整词 / 中文 bigram / 标点切分（协议冻结口径）")
    void tokenizeFormula() {
        List<String> toks = LocalLexicalRerankAdapter.tokenize("CNC 冷却-系统E-01");
        assertEquals(List.of("cnc", "冷却", "系统", "e", "01"), toks);
        // 单字符非 ASCII 段取单字（flushNonAscii 段长 1 分支）
        assertEquals(List.of("泵"), LocalLexicalRerankAdapter.tokenize("泵"));
        // 空串与纯标点
        assertTrue(LocalLexicalRerankAdapter.tokenize("").isEmpty());
        assertTrue(LocalLexicalRerankAdapter.tokenize("——  ，。").isEmpty());
    }

    @Test
    @DisplayName("BM25 手算场景：tf 高频者 top1，零命中者保留在尾（纯重排不删除成员）")
    void bm25HandComputedOrder() {
        LocalLexicalRerankAdapter adapter = new LocalLexicalRerankAdapter(props(0));
        // 手算（k1=1.2,b=0.75,Robertson IDF）：查询"冷却" df=2,N=3 →
        // c1(tf=3)≈0.705 > c2(tf=1)≈0.620 > c0(0)
        List<RerankCandidate> cs = cands(
                "主轴温度超过上限 停机检查",
                "冷却泵源 冷却流量 冷却系统",
                "冷却水塔");
        RerankResult r = adapter.rerank("冷却", cs, 3);
        assertEquals(List.of(1, 2, 0), r.ordered().stream()
                .map(RerankResult.Item::candidateOrdinal).toList());
        assertEquals(3, r.ordered().size(), "D2 集合不变性：零命中候选不得被删除");
        assertTrue(r.flags().contains(RerankFlags.REORDERED));
        // score 降序自洽
        for (int i = 1; i < r.ordered().size(); i++) {
            assertTrue(r.ordered().get(i - 1).score() >= r.ordered().get(i).score());
        }
    }

    @Test
    @DisplayName("同分稳定决胜：全零分保持原向量序 + IDENTITY")
    void tiesKeepOriginalOrder() {
        LocalLexicalRerankAdapter adapter = new LocalLexicalRerankAdapter(props(0));
        RerankResult r = adapter.rerank("不存在词", cands("甲内容", "乙内容", "丙内容"), 3);
        assertEquals(List.of(0, 1, 2), r.ordered().stream()
                .map(RerankResult.Item::candidateOrdinal).toList());
        assertTrue(r.flags().contains(RerankFlags.IDENTITY));
    }

    @Test
    @DisplayName("确定性：同输入两次运行输出序列与分数完全一致（离线可复算前提）")
    void deterministicAcrossRuns() {
        LocalLexicalRerankAdapter adapter = new LocalLexicalRerankAdapter(props(0));
        List<RerankCandidate> cs = cands("冷却流量 24 L/min", "主轴跳动 12.5 mm", "冷却系统维护");
        RerankResult a = adapter.rerank("冷却", cs, 3);
        RerankResult b = adapter.rerank("冷却", cs, 3);
        assertEquals(a.ordered(), b.ordered());
    }

    @Test
    @DisplayName("max-output 截断：top1 保留 + OUTPUT_TRUNCATED + rank 连续")
    void maxOutputTruncation() {
        LocalLexicalRerankAdapter adapter = new LocalLexicalRerankAdapter(props(2));
        RerankResult r = adapter.rerank("冷却", cands(
                "主轴温度超过上限", "冷却泵源 冷却流量", "冷却水塔"), 3);
        assertEquals(2, r.ordered().size());
        assertTrue(r.flags().contains(RerankFlags.OUTPUT_TRUNCATED));
        assertEquals(0, r.ordered().get(0).rank());
        assertEquals(1, r.ordered().get(1).rank());
    }

    @Test
    @DisplayName("空候选：NOOP_EMPTY 原样返回")
    void emptyCandidates() {
        RerankResult r = new LocalLexicalRerankAdapter(props(0)).rerank("q", List.of(), 5);
        assertTrue(r.ordered().isEmpty());
        assertTrue(r.flags().contains(RerankFlags.NOOP_EMPTY));
    }
}
