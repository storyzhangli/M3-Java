package com.zl.d39.one.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * one_ 轨 PG 向量表配置（命名空间 {@code app.one.pgvector}）。
 *
 * <p>连接复用 local 轨 {@code pgVectorDataSource}/{@code pgVectorJdbcTemplate}（同一 PG 库），
 * 仅表名独立：{@code one_d39_vector_store}，与 local 表 {@code d39_vector_store} 物理隔离。</p>
 *
 * <p>表名强制 {@code one_} 前缀（H2 契约结构性守卫），防止误配覆盖 local 表。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.one.pgvector")
public class OnePgVectorProperties {

    /** one_ 轨正式向量表名：固定前缀 one_，禁止指向 local 表。 */
    private String table = "one_d39_vector_store";

    /** Schema 名（与 local 轨同库同 schema，不同表）。 */
    private String schema = "public";

    /** 是否由程序初始化 Schema（建扩展、建表、建 HNSW 索引）。 */
    private boolean initializeSchema = true;

    /** 单批最大文档数。 */
    private int maxDocumentBatchSize = 10000;

    @PostConstruct
    public void validate() {
        if (table == null || !table.startsWith("one_")) {
            throw new IllegalArgumentException("app.one.pgvector.table 必须以 one_ 前缀命名（双轨隔离契约），当前=" + table);
        }
        if ("d39_vector_store".equals(table) || "vector_store".equals(table)) {
            throw new IllegalArgumentException("one_ 轨表名禁止与 local 表/默认表重名");
        }
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("app.one.pgvector.schema 不能为空");
        }
        if (maxDocumentBatchSize < 1) {
            throw new IllegalArgumentException("app.one.pgvector.maxDocumentBatchSize 必须为正");
        }
    }
}