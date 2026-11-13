package com.zl.d30.document.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d30.common.GlobalExceptionHandler;
import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.chunk.model.ChunkResult;
import com.zl.d30.document.chunk.service.DocumentChunkService;
import com.zl.d30.document.embedding.controller.EmbeddingController;
import com.zl.d30.document.embedding.dto.CompatibilityCheckRequest;
import com.zl.d30.document.embedding.dto.EmbeddingViewMapper;
import com.zl.d30.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d30.document.embedding.model.EmbeddingRecord;
import com.zl.d30.document.embedding.service.DocumentEmbeddingService;
import com.zl.d30.document.embedding.service.VectorIndexCompatibilityService;
import com.zl.d30.document.service.DocumentPageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Embedding 入口 MockMvc 切片测试：验证统一 Result 协议、向量化与兼容性响应。
 */
class EmbeddingControllerTest {

    private DocumentPageService pageService;

    private DocumentChunkService chunkService;

    private DocumentEmbeddingService embeddingService;

    private VectorIndexCompatibilityService compatibilityService;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        pageService = mock(DocumentPageService.class);
        chunkService = mock(DocumentChunkService.class);
        embeddingService = mock(DocumentEmbeddingService.class);
        compatibilityService = mock(VectorIndexCompatibilityService.class);
        EmbeddingController controller = new EmbeddingController(
                pageService, chunkService, embeddingService, compatibilityService,
                new EmbeddingViewMapper());
        mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private EmbeddingModelMetadata currentMetadata() {
        return EmbeddingModelMetadata.builder()
                .provider("local")
                .modelName("local-hashing-embedding")
                .modelVersion("local-v1")
                .dimensions(256)
                .vectorSpaceId("local|local-hashing-embedding|local-v1|256")
                .indexVersion(1)
                .build();
    }

    @Test
    void modelEndpointReturnsMetadata() throws Exception {
        when(compatibilityService.currentIndex()).thenReturn(currentMetadata());
        mockMvc.perform(get("/api/v1/embeddings/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dimensions").value(256));
    }

    @Test
    void embedEndpointReturnsRecords() throws Exception {
        ChunkResult chunkResult = new ChunkResult();
        chunkResult.setFixedChunks(List.of(new Chunk()));
        when(pageService.listPages("doc-1")).thenReturn(List.of());
        when(chunkService.process(any())).thenReturn(chunkResult);

        EmbeddingRecord record = new EmbeddingRecord();
        record.setDocumentId("doc-1");
        record.setDimensions(256);
        record.setVector(new float[256]);
        when(embeddingService.embedChunks(any())).thenReturn(List.of(record));

        mockMvc.perform(post("/api/v1/documents/doc-1/embeddings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].dimensions").value(256))
                .andExpect(jsonPath("$.data[0].vectorPreview").isArray());
    }

    @Test
    void compatibilityEndpointReturnsDecision() throws Exception {
        CompatibilityCheckRequest request = new CompatibilityCheckRequest();
        request.setProvider("openai");
        request.setModelName("text-embedding-3-large");
        request.setModelVersion("v1");
        request.setDimensions(3072);

        when(compatibilityService.currentIndex()).thenReturn(currentMetadata());
        when(compatibilityService.check(any())).thenReturn(
                com.zl.d30.document.embedding.model.CompatibilityDecision.DIMENSION_MISMATCH);

        mockMvc.perform(post("/api/v1/embeddings/index/compatibility")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.compatible").value(false))
                .andExpect(jsonPath("$.data.decision").value("DIMENSION_MISMATCH"));
    }

    @Test
    void compatibilityValidationErrorReturnsParamInvalid() throws Exception {
        CompatibilityCheckRequest request = new CompatibilityCheckRequest();
        request.setProvider("");
        request.setModelName("m");
        request.setModelVersion("v1");
        request.setDimensions(10);

        mockMvc.perform(post("/api/v1/embeddings/index/compatibility")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }
}
