package com.zl.d30.document.vectorstore;

import com.zl.d30.document.embedding.config.EmbeddingProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * pgvector 装配配置单元测试（不连接数据库，initializeSchema=false，build 不触发生命周期）。
 */
class PgVectorStoreConfigurationTest {

    private final PgVectorStoreConfiguration configuration = new PgVectorStoreConfiguration();

    private HikariDataSource dataSource;

    @AfterEach
    void tearDown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Test
    void dataSourceUsesPgJdbcUrl() {
        dataSource = configuration.pgVectorDataSource(properties());
        assertEquals("jdbc:postgresql://192.168.200.153:5432/spring_ai", dataSource.getJdbcUrl());
        assertEquals("postgres", dataSource.getUsername());
        assertEquals("pgvector-pool", dataSource.getPoolName());
    }

    @Test
    void jdbcTemplateBindsDataSource() {
        dataSource = configuration.pgVectorDataSource(properties());
        JdbcTemplate jdbcTemplate = configuration.pgVectorJdbcTemplate(dataSource);
        assertSame(dataSource, jdbcTemplate.getDataSource());
    }

    @Test
    void vectorStoreBuiltWithMatchingDimensions() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(256);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PgVectorStore store = configuration.pgVectorStore(
                jdbcTemplate, embeddingModel, properties(), embeddingProperties(256));
        assertEquals(PgVectorStore.PgDistanceType.COSINE_DISTANCE, store.getDistanceType());
        assertNotNull(store);
    }

    @Test
    void vectorStoreRejectsDimensionMismatch() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(384);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> configuration.pgVectorStore(
                        jdbcTemplate, embeddingModel, properties(), embeddingProperties(256)));
        assertTrue(ex.getMessage().contains("维度不一致"));
    }

    private PgVectorStoreProperties properties() {
        PgVectorStoreProperties props = new PgVectorStoreProperties();
        props.setPassword("secret");
        props.setInitializeSchema(false);
        return props;
    }

    private EmbeddingProperties embeddingProperties(int dimensions) {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setDimensions(dimensions);
        return props;
    }
}
