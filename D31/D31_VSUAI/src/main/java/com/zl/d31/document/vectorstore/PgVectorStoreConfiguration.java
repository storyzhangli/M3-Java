package com.zl.d31.document.vectorstore;

import com.zaxxer.hikari.HikariDataSource;
import com.zl.d31.document.embedding.config.EmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * pgvector 手动装配：独立 PG 数据源 + 专用 JdbcTemplate + {@link PgVectorStore}。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>PG 数据源<b>不标注 {@code @Primary}</b>，MySQL 仍是主业务数据源，MyBatis-Plus 不受影响；</li>
 *   <li>向量维度直接取自 {@link EmbeddingProperties}，并与 {@link EmbeddingModel} 真实维度
 *       做一致性断言，从源头保证模型与表维度一致，杜绝"只改库不同步模型"；</li>
 *   <li>距离类型固定 COSINE_DISTANCE、索引类型 HNSW，与本地模型 L2 归一化 + 余弦口径一致；</li>
 *   <li>因自定义了 {@link VectorStore} Bean，Spring AI 的 PgVectorStore 自动装配
 *       （{@code @ConditionalOnMissingBean}）自动回退，不会基于主 MySQL JdbcTemplate 误建。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.pgvector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorStoreConfiguration {

    /**
     * 主业务数据源（MySQL）：显式声明并标注 {@link Primary}。
     *
     * <p>引入第二个 DataSource 后，Spring Boot 的 {@code DataSourceConfiguration.Hikari}
     * 因 {@code @ConditionalOnMissingBean(DataSource.class)} 会回退，故必须显式重建
     * MySQL 数据源，保证 MyBatis-Plus 与事务管理器仍绑定 MySQL，避免业务表被误路由到 PG。</p>
     */
    @Primary
    @Bean(name = "dataSource", destroyMethod = "close")
    public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * 主业务 JdbcTemplate：绑定 MySQL 主数据源并标注 {@link Primary}。
     *
     * <p>一旦手动定义了 {@link #pgVectorJdbcTemplate}，Spring Boot 的
     * {@code JdbcTemplateAutoConfiguration} 因 {@code @ConditionalOnMissingBean(JdbcTemplate.class)}
     * 整体回退，不再创建默认 jdbcTemplate；此时容器内唯一的 JdbcTemplate 是 PG 专用的，
     * 任何未加限定符的 JdbcTemplate 注入都会被误路由到 PG。这里显式补一个绑定 MySQL 的
     * 主 JdbcTemplate，保证"未限定即 MySQL"的语义与 {@link Primary} 数据源一致。</p>
     */
    @Primary
    @Bean(name = "jdbcTemplate")
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 独立 PostgreSQL 数据源（非主数据源），仅服务向量存储。
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource pgVectorDataSource(PgVectorStoreProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setJdbcUrl(properties.jdbcUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setPoolName("pgvector-pool");
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(10_000L);
        dataSource.setIdleTimeout(600_000L);
        dataSource.setMaxLifetime(1_800_000L);
        return dataSource;
    }

    /**
     * 专用 JdbcTemplate：绑定 PG 数据源，与主（MySQL）JdbcTemplate 隔离。
     */
    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") DataSource pgVectorDataSource) {
        return new JdbcTemplate(pgVectorDataSource);
    }

    /**
     * 正式向量存储：维度/距离/索引与 EmbeddingModel 严格对齐。
     */
    @Bean
    public PgVectorStore pgVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                       EmbeddingModel embeddingModel,
                                       PgVectorStoreProperties pgVectorProperties,
                                       EmbeddingProperties embeddingProperties) {
        int dimensions = embeddingProperties.getDimensions();
        int modelDimensions = embeddingModel.dimensions();
        if (modelDimensions != dimensions) {
            throw new IllegalStateException(
                    "EmbeddingModel 维度与配置维度不一致：model=" + modelDimensions
                            + "，config=" + dimensions + "，禁止创建向量表");
        }
        // 注意：PgVectorStore.afterPropertiesSet 的顺序是「先 validateTableSchema，后 initializeSchema」。
        // 全新库下表尚不存在，若开启 vectorTableValidationsEnabled 会在建表前即失败，
        // 因此这里采用 Builder 默认（false）；表结构正确性改由集成测试经 catalog 独立核验
        // （pg_attribute.format_type 必须为 vector(384)），证据更直接、可追溯。
        return PgVectorStore.builder(pgVectorJdbcTemplate, embeddingModel)
                .schemaName(pgVectorProperties.getSchema())
                .vectorTableName(pgVectorProperties.getTable())
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(pgVectorProperties.isInitializeSchema())
                .maxDocumentBatchSize(pgVectorProperties.getMaxDocumentBatchSize())
                .build();
    }
}
