package com.zl.d30.document.embedding;

import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.chunk.model.ChunkStrategy;
import com.zl.d30.document.embedding.config.EmbeddingProperties;
import com.zl.d30.document.embedding.local.LocalHashingEmbeddingModel;
import com.zl.d30.document.embedding.model.EmbeddingRecord;
import com.zl.d30.document.embedding.service.DocumentEmbeddingService;
import com.zl.d30.document.embedding.support.EmbeddingFailureClassifier;
import com.zl.d30.document.embedding.support.EmbeddingRetryExecutor;
import com.zl.d30.document.embedding.support.EmbeddingServiceException;
import com.zl.d30.document.embedding.support.VectorValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 文档向量化服务测试：空/空白/超长拒绝、正常批量、重试与校验失败。
 */
class DocumentEmbeddingServiceTest {

    private EmbeddingProperties properties;

    private VectorValidator validator;

    @BeforeEach
    void setUp() {
        properties = new EmbeddingProperties();
        properties.setDimensions(64);
        properties.setMaxInputLength(100);
        validator = new VectorValidator();
    }

    private DocumentEmbeddingService serviceWith(org.springframework.ai.embedding.EmbeddingModel model) {
        EmbeddingRetryExecutor retry = new EmbeddingRetryExecutor(
                properties, new EmbeddingFailureClassifier(), ms -> { });
        return new DocumentEmbeddingService(model, properties, validator, retry);
    }

    private Chunk chunk(String content) {
        Chunk chunk = new Chunk();
        chunk.setDocumentId("doc-1");
        chunk.setTitle("标题");
        chunk.setVersion(1);
        chunk.setStrategy(ChunkStrategy.FIXED_SIZE);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        return chunk;
    }

    @Test
    void normalBatchProducesRecords() {
        LocalHashingEmbeddingModel model = new LocalHashingEmbeddingModel(properties);
        DocumentEmbeddingService service = serviceWith(model);

        List<EmbeddingRecord> records = service.embedChunks(List.of(
                chunk("第一条向量内容"), chunk("第二条向量内容")));

        assertEquals(2, records.size());
        assertEquals(64, records.get(0).getDimensions());
        assertEquals(properties.vectorSpaceId(), records.get(0).getVectorSpaceId());
    }

    @Test
    void emptyChunkRejected() {
        DocumentEmbeddingService service = serviceWith(new LocalHashingEmbeddingModel(properties));
        assertThrows(EmbeddingServiceException.class,
                () -> service.embedChunks(List.of(chunk(""))));
    }

    @Test
    void blankChunkRejected() {
        DocumentEmbeddingService service = serviceWith(new LocalHashingEmbeddingModel(properties));
        assertThrows(EmbeddingServiceException.class,
                () -> service.embedChunks(List.of(chunk("   "))));
    }

    @Test
    void oversizeChunkRejectedWithoutTruncation() {
        DocumentEmbeddingService service = serviceWith(new LocalHashingEmbeddingModel(properties));
        String longText = "a".repeat(120);
        EmbeddingServiceException ex = assertThrows(EmbeddingServiceException.class,
                () -> service.embedChunks(List.of(chunk(longText))));
        assertEquals(false, ex.isRetryable());
    }

    @Test
    void emptyChunkListRejected() {
        DocumentEmbeddingService service = serviceWith(new LocalHashingEmbeddingModel(properties));
        assertThrows(EmbeddingServiceException.class, () -> service.embedChunks(List.of()));
    }

    @Test
    void retryableFailureThenSuccess() {
        StubEmbeddingModel stub = new StubEmbeddingModel(64, 2, true);
        DocumentEmbeddingService service = serviceWith(stub);

        List<EmbeddingRecord> records = service.embedChunks(List.of(chunk("retry me")));
        assertEquals(1, records.size());
        assertEquals(3, stub.getCallCount());
    }

    @Test
    void wrongDimensionOutputRejected() {
        StubEmbeddingModel stub = new StubEmbeddingModel(32, 0, true);
        DocumentEmbeddingService service = serviceWith(stub);
        assertThrows(EmbeddingServiceException.class,
                () -> service.embedChunks(List.of(chunk("dimension check"))));
    }
}
