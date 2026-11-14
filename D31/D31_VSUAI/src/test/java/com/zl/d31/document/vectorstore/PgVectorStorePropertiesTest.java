package com.zl.d31.document.vectorstore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pgvector 配置属性绑定与校验测试（不连接数据库）。
 */
class PgVectorStorePropertiesTest {

    @Test
    void defaultsAreExpected() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        assertEquals("192.168.200.153", properties.getHost());
        assertEquals(5432, properties.getPort());
        assertEquals("spring_ai", properties.getDatabase());
        assertEquals("public", properties.getSchema());
        assertEquals("d31_vector_store", properties.getTable());
        assertTrue(properties.isInitializeSchema());
    }

    @Test
    void jdbcUrlIsWellFormed() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setPassword("secret");
        assertEquals("jdbc:postgresql://192.168.200.153:5432/spring_ai", properties.jdbcUrl());
    }

    @Test
    void validationPassesWhenPasswordPresent() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setPassword("secret");
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void blankPasswordRejected() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setPassword("  ");
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void invalidPortRejected() {
        PgVectorStoreProperties properties = new PgVectorStoreProperties();
        properties.setPassword("secret");
        properties.setPort(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void binderBindsNamespace() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.pgvector.host", "10.0.0.1",
                "app.pgvector.table", "custom_table",
                "app.pgvector.password", "env-secret"));
        Binder binder = new Binder(source);
        PgVectorStoreProperties bound = binder
                .bindOrCreate("app.pgvector", PgVectorStoreProperties.class);
        assertEquals("10.0.0.1", bound.getHost());
        assertEquals("custom_table", bound.getTable());
        assertEquals("env-secret", bound.getPassword());
    }
}
