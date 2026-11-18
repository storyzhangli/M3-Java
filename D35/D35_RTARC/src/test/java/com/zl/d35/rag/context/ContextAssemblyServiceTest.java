package com.zl.d35.rag.context;

import com.zl.d35.document.retrieval.dto.SimilarityMatchView;
import com.zl.d35.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextAssemblyService} 单元测试：来源编号、去重、预算与页码/片段映射。
 */
class ContextAssemblyServiceTest {

    private RagProperties properties() {
        RagProperties props = new RagProperties();
        props.setContextBudgetChars(6000);
        props.setQuotedSnippetMaxChars(200);
        return props;
    }

    private SimilarityMatchView match(String chunkId, String content, Map<String, Object> metadata) {
        return SimilarityMatchView.from(chunkId, content, metadata, 0.2d, null);
    }

    @Test
    @DisplayName("顺序分配 S1/S2，并映射真实页码/段落与引用片段")
    void assignRefsAndMapLocation() {
        SimilarityMatchView m1 = match("chunk-1", "第一段正文内容",
                Map.of("documentId", "doc-1", "title", "手册一", "version", 1,
                        "strategy", "PARAGRAPH", "chunkIndex", 0,
                        "page", 3, "lastPage", 4, "paragraphIndex", 2));
        SimilarityMatchView m2 = match("chunk-2", "第二段正文内容",
                Map.of("documentId", "doc-2", "title", "手册二", "version", 1,
                        "strategy", "PARAGRAPH", "chunkIndex", 1));

        ContextAssemblyResult result = new ContextAssemblyService(properties())
                .assemble(List.of(m1, m2));

        assertEquals(2, result.getEvidence().size());
        EvidenceItem e1 = result.getEvidence().get(0);
        assertEquals("S1", e1.getSourceRef());
        assertEquals(3, e1.getPage());
        assertEquals(4, e1.getLastPage());
        assertEquals(2, e1.getParagraphIndex());
        assertNotNull(e1.getQuotedSnippet());

        EvidenceItem e2 = result.getEvidence().get(1);
        assertEquals("S2", e2.getSourceRef());
        assertNull(e2.getPage());
    }

    @Test
    @DisplayName("引用片段按上限截取，模型不参与")
    void snippetTruncatedByConfig() {
        RagProperties props = properties();
        props.setQuotedSnippetMaxChars(10);
        SimilarityMatchView m = match("chunk-1", "abcdefghijklmnopqrstuvwxyz",
                Map.of("documentId", "doc-1", "strategy", "PARAGRAPH"));

        ContextAssemblyResult result = new ContextAssemblyService(props).assemble(List.of(m));

        assertEquals(10, result.getEvidence().get(0).getQuotedSnippet().length());
        assertTrue(result.getEvidence().get(0).getQuotedSnippet().endsWith("…"));
    }

    @Test
    @DisplayName("相同 chunkId 去重，保留首次出现")
    void dedupByChunkId() {
        SimilarityMatchView m1 = match("chunk-1", "内容A",
                Map.of("documentId", "doc-1", "strategy", "PARAGRAPH"));
        SimilarityMatchView m2 = match("chunk-1", "内容A重复",
                Map.of("documentId", "doc-1", "strategy", "PARAGRAPH"));

        ContextAssemblyResult result = new ContextAssemblyService(properties())
                .assemble(List.of(m1, m2));

        assertEquals(1, result.getEvidence().size());
        assertTrue(result.getExcluded().stream().anyMatch(e -> "DEDUP".equals(e.reason())));
    }

    @Test
    @DisplayName("空内容/无 ID 记录按 INVALID 排除")
    void invalidCandidatesExcluded() {
        SimilarityMatchView invalid = SimilarityMatchView.from("chunk-x", " ",
                Map.of(), null, null);
        ContextAssemblyResult result = new ContextAssemblyService(properties())
                .assemble(List.of(invalid));

        assertTrue(result.getEvidence().isEmpty());
        assertTrue(result.getExcluded().stream().anyMatch(e -> "INVALID".equals(e.reason())));
    }

    @Test
    @DisplayName("超预算证据按 BUDGET 排除")
    void budgetExclusion() {
        RagProperties props = properties();
        props.setContextBudgetChars(50);
        SimilarityMatchView m1 = match("chunk-1", "内容一".repeat(20),
                Map.of("documentId", "doc-1", "strategy", "PARAGRAPH"));
        SimilarityMatchView m2 = match("chunk-2", "内容二".repeat(20),
                Map.of("documentId", "doc-2", "strategy", "PARAGRAPH"));

        ContextAssemblyResult result = new ContextAssemblyService(props)
                .assemble(List.of(m1, m2));

        assertTrue(result.getExcluded().stream().anyMatch(e -> "BUDGET".equals(e.reason())));
    }
}
