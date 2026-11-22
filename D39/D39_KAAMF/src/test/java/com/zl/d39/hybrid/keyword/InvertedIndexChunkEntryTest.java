package com.zl.d39.hybrid.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D39-R01 C01 单测：{@link InvertedIndex.ChunkEntry} 权限三字段透传与
 * {@code filterFields()} 映射（H6 依据：canonical 指纹行不含权限键，
 * 由 D39Probe/回归阶段在真实 PG 上以指纹恒等证明；此处固定显式字段契约）。
 */
class InvertedIndexChunkEntryTest {

    @Test
    @DisplayName("13 参 ChunkEntry：权限三字段映射进 filterFields（非 Map，显式字段契约）")
    void filterFieldsCarriesPermissionTriple() {
        InvertedIndex.ChunkEntry entry = new InvertedIndex.ChunkEntry(
                "chunk-1", "内容", "sha-1", 2,
                "doc-1", "标题", 1, "STRUCTURE",
                0, "split-v1", 10,
                "T1", "D1", "DEV-A");
        KeywordFilterCriteria.ChunkFields fields = entry.filterFields();
        assertThat(fields.documentId()).isEqualTo("doc-1");
        assertThat(fields.title()).isEqualTo("标题");
        assertThat(fields.version()).isEqualTo(1);
        assertThat(fields.strategy()).isEqualTo("STRUCTURE");
        assertThat(fields.tenantId()).isEqualTo("T1");
        assertThat(fields.deptId()).isEqualTo("D1");
        assertThat(fields.deviceId()).isEqualTo("DEV-A");
    }

    @Test
    @DisplayName("遗留行（无权限键）：三字段容忍为 null，不补造（§3.7 缺字段容忍）")
    void legacyRowToleratesNulls() {
        InvertedIndex.ChunkEntry legacy = new InvertedIndex.ChunkEntry(
                "chunk-2", "内容", "sha-2", 2,
                "doc-2", "标题", 1, "STRUCTURE",
                1, "split-v1", 10,
                null, null, null);
        KeywordFilterCriteria.ChunkFields fields = legacy.filterFields();
        assertThat(fields.tenantId()).isNull();
        assertThat(fields.deptId()).isNull();
        assertThat(fields.deviceId()).isNull();
        // 匹配端 fail-closed（与 KeywordFilterCriteriaTest 闭环）
        KeywordFilterCriteria criteria = new KeywordFilterCriteria(
                null, null, null, null,
                new com.zl.d39.access.PermissionScope("T1", "D1", java.util.Set.of("DEV-A"), false, "*"));
        assertThat(criteria.matches(fields)).isFalse();
    }
}
