package com.zl.d30.document.vectorstore;

import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.vectorstore.dto.VectorMatchView;
import com.zl.d30.document.vectorstore.dto.VectorSearchRequest;
import com.zl.d30.document.vectorstore.dto.VectorWriteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 向量存储服务单元测试：VectorStore 与 JdbcTemplate 均为 Mock，不连接数据库。
 */
class PgVectorStoreServiceTest {

    private VectorStore vectorStore;

    private JdbcTemplate jdbcTemplate;

    private PgVectorStoreService service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new PgVectorStoreService(vectorStore, jdbcTemplate, properties());
    }

    @Test
    void addItemsDelegatesToVectorStore() {
        VectorWriteRequest request = new VectorWriteRequest();
        VectorWriteRequest.Item item = new VectorWriteRequest.Item();
        item.setText("hello pgvector");
        item.setMetadata(Map.of("source", "unit-test"));
        request.setItems(List.of(item));

        int written = service.addItems(request.getItems());

        assertEquals(1, written);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        assertEquals("hello pgvector", captor.getValue().get(0).getText());
        assertEquals("unit-test", captor.getValue().get(0).getMetadata().get("source"));
    }

    @Test
    void blankItemRejected() {
        VectorWriteRequest.Item item = new VectorWriteRequest.Item();
        item.setText("   ");
        assertThrows(IllegalArgumentException.class, () -> service.addItems(List.of(item)));
        verify(vectorStore, never()).add(any(List.class));
    }

    @Test
    void addChunksCarriesMetadata() {
        Chunk chunk = new Chunk();
        chunk.setDocumentId("doc-1");
        chunk.setTitle("标题");
        chunk.setVersion(1);
        chunk.setChunkIndex(0);
        chunk.setContent("chunk 内容");

        int written = service.addChunks(List.of(chunk));

        assertEquals(1, written);
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        Document document = captor.getValue().get(0);
        assertEquals("doc-1", document.getMetadata().get("documentId"));
        assertEquals(0, document.getMetadata().get("chunkIndex"));
    }

    @Test
    void searchMapsDocumentsToViews() {
        Document document = Document.builder()
                .text("匹配文本")
                .metadata(Map.of("k", "v"))
                .score(0.91)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        VectorSearchRequest request = new VectorSearchRequest();
        request.setQuery("查询");
        request.setTopK(1);
        List<VectorMatchView> views = service.search(request);

        assertEquals(1, views.size());
        assertEquals(0.91, views.get(0).getScore(), 1e-9);
        assertEquals("匹配文本", views.get(0).getText());
    }

    @Test
    void countDelegatesToJdbcTemplate() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
        assertEquals(7L, service.count());
    }

    private PgVectorStoreProperties properties() {
        PgVectorStoreProperties props = new PgVectorStoreProperties();
        props.setPassword("secret");
        return props;
    }
}
