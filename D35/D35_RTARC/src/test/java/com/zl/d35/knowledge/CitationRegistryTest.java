package com.zl.d35.knowledge;

import com.zl.d35.dto.CitationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CitationRegistry} 单元测试：编号清洗、cited 语义、URL 安全与扩展字段。
 */
class CitationRegistryTest {

    private KnowledgeChunk chunk(String id, String url) {
        return new KnowledgeChunk("doc-" + id, "chunk-" + id, "手册 " + id,
                "MANUAL", "章节 " + id, 2, "摘录 " + id, url);
    }

    @Test
    @DisplayName("合法编号保留，未知编号清洗并计数")
    void stripUnknownMarkers() {
        CitationRegistry registry = new CitationRegistry(List.of(chunk("1", null)));
        String out = registry.validateAndStrip("正文 [S1] 与伪造 [S99] 结尾");

        assertTrue(out.contains("[S1]"));
        assertFalse(out.contains("[S99]"));
        assertEquals(1, registry.getDroppedUnknownMarkers());
    }

    @Test
    @DisplayName("citedCitations 的 cited=true，allCitations 的 cited=false")
    void citedSemantics() {
        CitationRegistry registry = new CitationRegistry(List.of(chunk("1", null)));
        registry.validateAndStrip("答案 [S1]");

        CitationDto cited = registry.citedCitations().get(0);
        assertTrue(cited.cited());
        CitationDto all = registry.allCitations().get(0);
        assertFalse(all.cited());
    }

    @Test
    @DisplayName("危险 URL 经安全策略清洗为 null，并填充稳定/请求级标识")
    void urlSanitizedAndIdsMapped() {
        CitationRegistry registry = new CitationRegistry(
                List.of(chunk("1", "javascript:alert(1)")));
        registry.validateAndStrip("答案 [S1]");

        CitationDto dto = registry.citedCitations().get(0);
        assertNull(dto.url());
        assertEquals("doc-1", dto.sourceId());
        assertEquals("S1", dto.citationId());
        assertEquals("摘录 1", dto.quotedSnippet());
    }

    @Test
    @DisplayName("无来源注册表仍可清洗自造标记")
    void emptyRegistryStripsMarkers() {
        CitationRegistry registry = new CitationRegistry(List.of());
        assertFalse(registry.hasSources());
        String out = registry.validateAndStrip("普通文本 [S2]");
        assertFalse(out.contains("[S2]"));
        assertEquals("普通文本", out.trim());
        assertEquals(1, registry.getDroppedUnknownMarkers());
    }
}
