package com.zl.d32.document.retrieval;

import com.zl.d32.document.embedding.config.EmbeddingProperties;
import com.zl.d32.document.embedding.support.EmbeddingServiceException;
import com.zl.d32.document.embedding.support.VectorValidator;
import com.zl.d32.document.retrieval.dto.SimilarityMatchView;
import com.zl.d32.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d32.document.retrieval.dto.SimilaritySearchResultView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SimilaritySearchService} 单元测试：不连数据库，Mock 掉 VectorStore/EmbeddingModel，
 * 覆盖默认 Top-5、Top-K 边界、空白/超长、向量化失败、空向量、维度不符与过滤表达式构造。
 */
@ExtendWith(MockitoExtension.class)
class SimilaritySearchServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private EmbeddingModel embeddingModel;

    private EmbeddingProperties embeddingProperties;

    private VectorValidator vectorValidator;

    private SimilaritySearchService service;

    @BeforeEach
    void setUp() {
        embeddingProperties = new EmbeddingProperties();
        vectorValidator = new VectorValidator();
        service = new SimilaritySearchService(vectorStore, embeddingModel,
                embeddingProperties, vectorValidator);
    }

    @Test
    void defaultTopKIsFive() {
        stubValidEmbedding();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(sampleDocument()));

        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery("空压机过热怎么办");
        SimilaritySearchResultView result = service.search(request);

        assertEquals(5, result.getTopK());
        assertFalse(result.isFiltered());
        assertEquals(1, result.getTotal());
    }

    @Test
    void topKClampedToBounds() {
        stubValidEmbedding();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        SimilaritySearchRequest tooSmall = request("水泵异响", 0, null);
        assertEquals(1, service.search(tooSmall).getTopK());

        SimilaritySearchRequest tooLarge = request("水泵异响", 100, null);
        assertEquals(20, service.search(tooLarge).getTopK());
    }

    @Test
    void blankQueryRejected() {
        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery("   ");
        assertThrows(IllegalArgumentException.class, () -> service.search(request));
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void overlongQueryRejected() {
        String overlong = "x".repeat(embeddingProperties.getMaxInputLength() + 1);
        SimilaritySearchRequest request = request(overlong, 5, null);
        assertThrows(IllegalArgumentException.class, () -> service.search(request));
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void embeddingFailureRejected() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("boom"));
        SimilaritySearchRequest request = request("电机不启动", 5, null);
        assertThrows(IllegalArgumentException.class, () -> service.search(request));
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void zeroVectorRejected() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[384]);
        SimilaritySearchRequest request = request("。。。", 5, null);
        assertThrows(IllegalArgumentException.class, () -> service.search(request));
    }

    @Test
    void wrongDimensionRejected() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
        SimilaritySearchRequest request = request("维度不符", 5, null);
        assertThrows(EmbeddingServiceException.class, () -> service.search(request));
    }

    @Test
    void unfilteredMapsRealScoreAndProvenance() {
        stubValidEmbedding();
        Document document = sampleDocument();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        SimilaritySearchResultView result = service.search(request("空压机过热", 5, null));
        SimilarityMatchView match = result.getMatches().get(0);

        assertEquals("chunk-1", match.getChunkId());
        assertEquals("doc-1", match.getDocumentId());
        assertEquals("空压机维修手册", match.getTitle());
        assertEquals(2, match.getVersion());
        assertEquals(0, match.getChunkIndex());
        assertEquals(0.8d, match.getSimilarity(), 1e-9);
        assertEquals(0.2d, match.getDistance(), 1e-9);
        // 相似度与距离满足 1 - distance。
        assertEquals(1.0d - match.getDistance(), match.getSimilarity(), 1e-9);
        assertNotNull(match.getMetadata().get("chunkContentHash"));
    }

    @Test
    void filteredPassesFilterExpression() {
        stubValidEmbedding();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        SimilaritySearchRequest request = request("只看某文档", 5, null);
        request.setDocumentId("doc-9");
        request.setVersion(1);
        SimilaritySearchResultView result = service.search(request);

        assertTrue(result.isFiltered());
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        Filter.Expression expression = captor.getValue().getFilterExpression();
        assertNotNull(expression);
        assertEquals(Filter.ExpressionType.AND, expression.type());
    }

    @Test
    void unfilteredHasNoFilterExpression() {
        stubValidEmbedding();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        service.search(request("空间标识", 5, null));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        assertNotNull(captor.getValue());
        // 无过滤时不设置 filterExpression。
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getFilterExpression());
    }

    @Test
    void vectorSpaceIdPropagated() {
        stubValidEmbedding();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        SimilaritySearchResultView result = service.search(request("空间标识", 5, null));
        assertEquals(embeddingProperties.vectorSpaceId(), result.getVectorSpaceId());
    }

    private void stubValidEmbedding() {
        when(embeddingModel.embed(anyString())).thenReturn(validVector());
    }

    private float[] validVector() {
        float[] vector = new float[384];
        vector[0] = 1.0f;
        return vector;
    }

    private Document sampleDocument() {
        Map<String, Object> metadata = Map.of(
                "documentId", "doc-1",
                "title", "空压机维修手册",
                "version", 2,
                "strategy", "STRUCTURE",
                "chunkIndex", 0,
                "chunkContentHash", "hash-abc",
                "distance", 0.2d);
        return Document.builder()
                .id("chunk-1")
                .text("空压机过热时应检查冷却器与润滑油")
                .metadata(metadata)
                .score(0.8d)
                .build();
    }

    private SimilaritySearchRequest request(String query, Integer topK, Double threshold) {
        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery(query);
        request.setTopK(topK);
        request.setThreshold(threshold);
        return request;
    }
}
