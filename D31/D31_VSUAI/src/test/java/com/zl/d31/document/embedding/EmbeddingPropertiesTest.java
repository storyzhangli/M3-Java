package com.zl.d31.document.embedding;

import com.zl.d31.document.embedding.config.EmbeddingProperties;
import com.zl.d31.document.embedding.model.DistanceMetric;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Embedding 配置校验测试。
 */
class EmbeddingPropertiesTest {

    @Test
    void defaultsAreValid() {
        EmbeddingProperties properties = new EmbeddingProperties();
        assertDoesNotThrow(properties::validate);
        assertEquals("local", properties.getProvider());
        assertEquals(384, properties.getDimensions());
        assertEquals(DistanceMetric.COSINE, properties.getDistanceMetric());
    }

    @Test
    void vectorSpaceIdContainsAllParts() {
        EmbeddingProperties properties = new EmbeddingProperties();
        String id = properties.vectorSpaceId();
        assertTrue(id.contains("local"));
        assertTrue(id.endsWith("384"));
        assertEquals(4, id.split("\\|").length);
    }

    @Test
    void invalidDimensionsRejected() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setDimensions(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void invalidMaxAttemptsRejected() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setMaxAttempts(6);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void cloudProviderRequiresBaseUrlAndKey() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProvider("openai");
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties.setBaseUrl("https://api.example.com");
        properties.setApiKey("sk-xxx");
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void blankProviderRejected() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setProvider(" ");
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}
