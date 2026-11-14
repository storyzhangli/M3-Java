package com.zl.d31.document.embedding;

import com.zl.d31.document.embedding.config.EmbeddingProperties;
import com.zl.d31.document.embedding.model.CompatibilityDecision;
import com.zl.d31.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d31.document.embedding.service.VectorIndexCompatibilityService;
import com.zl.d31.document.embedding.support.EmbeddingServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 索引兼容性服务测试：同模型兼容、异维度拒绝、同维度异模型隔离、切换递增 indexVersion。
 */
class VectorIndexCompatibilityServiceTest {

    private VectorIndexCompatibilityService service;

    @BeforeEach
    void setUp() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setDimensions(384);
        service = new VectorIndexCompatibilityService(properties);
    }

    private EmbeddingModelMetadata metadata(String provider, String model, String version, int dimensions) {
        return EmbeddingModelMetadata.builder()
                .provider(provider)
                .modelName(model)
                .modelVersion(version)
                .dimensions(dimensions)
                .vectorSpaceId(EmbeddingModelMetadata.buildVectorSpaceId(provider, model, version, dimensions))
                .build();
    }

    @Test
    void sameModelIsCompatible() {
        EmbeddingModelMetadata same = metadata("local", "local-hashing-embedding", "local-v1", 384);
        assertEquals(CompatibilityDecision.COMPATIBLE, service.check(same));
    }

    @Test
    void differentDimensionsRejected() {
        EmbeddingModelMetadata other = metadata("local", "local-hashing-embedding", "local-v1", 512);
        assertEquals(CompatibilityDecision.DIMENSION_MISMATCH, service.check(other));
    }

    @Test
    void sameDimensionsDifferentModelIsolated() {
        EmbeddingModelMetadata other = metadata("openai", "text-embedding-3-small", "v1", 384);
        CompatibilityDecision decision = service.check(other);
        assertEquals(CompatibilityDecision.MODEL_SPACE_MISMATCH, decision);
        assertEquals(false, decision.isCompatible());
    }

    @Test
    void assertWritableThrowsOnIncompatible() {
        EmbeddingModelMetadata other = metadata("openai", "text-embedding-3-large", "v1", 3072);
        assertThrows(EmbeddingServiceException.class, () -> service.assertWritable(other));
    }

    @Test
    void switchModelIncrementsIndexVersion() {
        EmbeddingProperties newProperties = new EmbeddingProperties();
        newProperties.setProvider("openai");
        newProperties.setModel("text-embedding-3-small");
        newProperties.setModelVersion("v1");
        newProperties.setDimensions(1536);
        newProperties.setBaseUrl("https://api.example.com");
        newProperties.setApiKey("sk-test");

        EmbeddingModelMetadata switched = service.switchModel(newProperties);

        assertEquals(2, switched.getIndexVersion());
        assertEquals(1536, switched.getDimensions());
        assertEquals(switched, service.currentIndex());
    }

    @Test
    void switchToSameSpaceKeepsIndex() {
        EmbeddingProperties same = new EmbeddingProperties();
        EmbeddingModelMetadata result = service.switchModel(same);
        assertEquals(1, result.getIndexVersion());
    }
}
