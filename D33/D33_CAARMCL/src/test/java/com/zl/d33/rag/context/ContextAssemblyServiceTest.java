package com.zl.d33.rag.context;

import com.zl.d33.document.retrieval.dto.SimilarityMatchView;
import com.zl.d33.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文组装服务单测：有效性、去重、顺序、预算与来源映射。
 */
class ContextAssemblyServiceTest {

    private ContextAssemblyService serviceWith(int budgetChars) {
        RagProperties properties = new RagProperties();
        properties.setContextBudgetChars(budgetChars);
        return new ContextAssemblyService(properties);
    }

    private SimilarityMatchView match(String chunkId, String content, double distance) {
        return SimilarityMatchView.from(chunkId, content, Map.of(
                "documentId", "doc-1",
                "title", "测试文档",
                "version", 1,
                "chunkIndex", 0,
                "strategy", "STRUCTURE"), distance, null);
    }

    @Test
    @DisplayName("保留检索顺序并分配连续来源标识 S1/S2")
    void shouldPreserveOrderAndAssignSourceRefs() {
        ContextAssemblyService service = serviceWith(6000);
        List<SimilarityMatchView> candidates = List.of(
                match("c1", "内容一", 0.10),
                match("c2", "内容二", 0.20),
                match("c3", "内容三", 0.30));

        ContextAssemblyResult result = service.assemble(candidates);

        assertEquals(3, result.getEvidence().size());
        assertEquals("S1", result.getEvidence().get(0).getSourceRef());
        assertEquals("S2", result.getEvidence().get(1).getSourceRef());
        assertEquals("S3", result.getEvidence().get(2).getSourceRef());
        assertEquals("c1", result.getEvidence().get(0).getChunkId());
        assertTrue(result.getContextText().contains("[S1]"));
        assertTrue(result.getContextText().contains("[S3]"));
    }

    @Test
    @DisplayName("按 chunkId 去重，保留首次出现位置")
    void shouldDeduplicateKeepingFirst() {
        ContextAssemblyService service = serviceWith(6000);
        List<SimilarityMatchView> candidates = List.of(
                match("c1", "首次", 0.10),
                match("c1", "重复", 0.11),
                match("c2", "其他", 0.20));

        ContextAssemblyResult result = service.assemble(candidates);

        assertEquals(2, result.getEvidence().size());
        assertEquals("首次", result.getEvidence().get(0).getContent());
        long dedup = result.getExcluded().stream()
                .filter(e -> e.reason().equals("DEDUP")).count();
        assertEquals(1, dedup);
        assertEquals(1, result.getBudget().getInvalidOrDedupChunks());
    }

    @Test
    @DisplayName("排除空内容或无稳定 ID 的无效记录")
    void shouldExcludeInvalid() {
        ContextAssemblyService service = serviceWith(6000);
        List<SimilarityMatchView> candidates = List.of(
                match("c1", "有效", 0.10),
                SimilarityMatchView.from("c2", "   ", Map.<String, Object>of(), 0.2, null),
                SimilarityMatchView.from(" ", "无ID", Map.<String, Object>of(), 0.3, null));

        ContextAssemblyResult result = service.assemble(candidates);

        assertEquals(1, result.getEvidence().size());
        long invalid = result.getExcluded().stream()
                .filter(e -> e.reason().equals("INVALID")).count();
        assertEquals(2, invalid);
    }

    @Test
    @DisplayName("超字符预算时按顺序剔除并记录，且上下文不超过预算")
    void shouldRespectBudget() {
        // 仅容纳第一个证据块的小预算
        ContextAssemblyService service = serviceWith(120);
        List<SimilarityMatchView> candidates = List.of(
                match("c1", "第一段", 0.10),
                match("c2", "第二段", 0.20),
                match("c3", "第三段", 0.30));

        ContextAssemblyResult result = service.assemble(candidates);

        assertEquals(1, result.getEvidence().size());
        assertEquals(2, result.getBudget().getBudgetExcludedChunks());
        assertTrue(result.getContextText().length() <= 120);
        assertTrue(result.getExcluded().stream().anyMatch(e -> e.reason().equals("BUDGET")));
    }

    @Test
    @DisplayName("预算单位明确为 CHARACTER，不伪称 Token")
    void shouldReportCharacterUnit() {
        ContextAssemblyService service = serviceWith(6000);
        ContextAssemblyResult result = service.assemble(List.of(match("c1", "x", 0.1)));

        assertEquals("CHARACTER", result.getBudget().getBudgetUnit());
        assertTrue(result.getBudget().getEstimationNote().contains("非 Token"));
        assertTrue(result.getBudget().getEstimationNote().contains("UTF-16"));
    }

    @Test
    @DisplayName("空候选返回空结果且无证据")
    void shouldHandleEmpty() {
        ContextAssemblyService service = serviceWith(6000);
        ContextAssemblyResult result = service.assemble(List.of());

        assertFalse(result.hasEvidence());
        assertEquals(0, result.getEvidence().size());
        assertEquals("", result.getContextText());
    }
}
