package com.zl.d30.document.embedding;

import com.zl.d30.document.embedding.config.EmbeddingProperties;
import com.zl.d30.document.embedding.local.LocalHashingEmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地哈希词面模型真实计算测试（非 Mock）：验证维度、确定性、归一化、数值有效性与中文。
 */
class LocalHashingEmbeddingModelTest {

    private static final int DIM = 256;

    private LocalHashingEmbeddingModel model;

    @BeforeEach
    void setUp() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setDimensions(DIM);
        model = new LocalHashingEmbeddingModel(properties);
    }

    @Test
    void dimensionsMatchConfig() {
        assertEquals(DIM, model.dimensions());
    }

    @Test
    void outputHasExpectedDimensions() {
        float[] vector = model.embed("hello embedding");
        assertEquals(DIM, vector.length);
    }

    @Test
    void sameTextProducesSameVector() {
        float[] first = model.embed("向量检索是 RAG 的核心环节");
        float[] second = model.embed("向量检索是 RAG 的核心环节");
        assertArrayEquals(first, second);
    }

    @Test
    void differentTextProducesDifferentVector() {
        float[] first = model.embed("java spring boot");
        float[] second = model.embed("python django flask");
        assertFalse(java.util.Arrays.equals(first, second));
    }

    @Test
    void vectorsAreL2Normalized() {
        float[] vector = model.embed("normalized vector test 归一化");
        assertEquals(1.0, l2Norm(vector), 1e-6);
    }

    @Test
    void noNaNOrInfinity() {
        float[] vector = model.embed("检查非法数值 NaN Infinity 123");
        for (float v : vector) {
            assertFalse(Float.isNaN(v));
            assertFalse(Float.isInfinite(v));
        }
    }

    @Test
    void chineseTextProducesNonZeroVector() {
        float[] vector = model.embed("中文向量化测试");
        assertTrue(l2Norm(vector) > 0.0);
    }

    @Test
    void sharedWordsHavePositiveCosine() {
        float[] a = model.embed("spring ai embedding model");
        float[] b = model.embed("spring ai embedding vector");
        assertTrue(cosine(a, b) > 0.5);
    }

    @Test
    void embedDocumentUsesText() {
        Document document = new Document("document embedding content");
        float[] viaDocument = model.embed(document);
        float[] viaText = model.embed("document embedding content");
        assertArrayEquals(viaText, viaDocument);
    }

    @Test
    void callReturnsOneEmbeddingPerInput() {
        EmbeddingRequest request = new EmbeddingRequest(
                List.of("text one", "text two", "text three"), null);
        EmbeddingResponse response = model.call(request);
        List<Embedding> embeddings = response.getResults();
        assertEquals(3, embeddings.size());
        for (Embedding embedding : embeddings) {
            assertEquals(DIM, embedding.getOutput().length);
        }
    }

    @Test
    void emptyTextReturnsZeroVectorWithCorrectDimensions() {
        float[] vector = model.embed("");
        assertEquals(DIM, vector.length);
        assertEquals(0.0, l2Norm(vector), 1e-12);
    }

    private double l2Norm(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    private float cosine(float[] a, float[] b) {
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return (float) dot;
    }
}
