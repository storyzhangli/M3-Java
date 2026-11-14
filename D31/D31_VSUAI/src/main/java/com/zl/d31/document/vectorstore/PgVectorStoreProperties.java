package com.zl.d31.document.vectorstore;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pgvector 连接与向量表配置（命名空间 {@code app.pgvector}）。
 *
 * <p>该数据源独立于 MySQL 主业务数据源，仅服务于向量存储；密码通过环境变量
 * {@code PGVECTOR_DB_PASSWORD} 注入，禁止把真实密钥硬编码进源码。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.pgvector")
public class PgVectorStoreProperties {

    /** 是否启用 pgvector 向量存储。 */
    private boolean enabled = true;

    /** PostgreSQL 主机。 */
    private String host = "192.168.200.153";

    /** PostgreSQL 端口。 */
    private int port = 5432;

    /** 数据库名。 */
    private String database = "spring_ai";

    /** 用户名。 */
    private String username = "postgres";

    /** 密码：由 application.yml 以环境变量占位注入，不在源码写死。 */
    private String password = "";

    /** Schema 名。 */
    private String schema = "public";

    /** 正式向量表名（独立命名，避免与默认 vector_store 及历史对象冲突）。 */
    private String table = "d31_vector_store";

    /** 是否由程序初始化 Schema（建扩展、建表、建 HNSW 索引）。 */
    private boolean initializeSchema = true;

    /** 单批最大文档数。 */
    private int maxDocumentBatchSize = 10000;

    /** 连接池大小。 */
    private int maximumPoolSize = 5;

    /**
     * 拼接 PostgreSQL JDBC URL。
     */
    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    /**
     * 启动校验：连接参数自洽，fail-fast。
     */
    @PostConstruct
    public void validate() {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("pgvector.host 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("pgvector.port 非法");
        }
        if (database == null || database.isBlank()) {
            throw new IllegalArgumentException("pgvector.database 不能为空");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("pgvector.username 不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("pgvector.password 必须通过环境变量注入");
        }
        if (schema == null || schema.isBlank() || table == null || table.isBlank()) {
            throw new IllegalArgumentException("pgvector schema/table 不能为空");
        }
        if (maxDocumentBatchSize < 1) {
            throw new IllegalArgumentException("pgvector.maxDocumentBatchSize 必须为正");
        }
    }
}
