package com.zl.d29.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d29.common.GlobalExceptionHandler;
import com.zl.d29.document.chunk.dto.ChunkViewMapper;
import com.zl.d29.document.chunk.model.Chunk;
import com.zl.d29.document.chunk.model.ChunkResult;
import com.zl.d29.document.chunk.model.ChunkStrategy;
import com.zl.d29.document.chunk.service.DocumentChunkService;
import com.zl.d29.document.controller.DocumentController;
import com.zl.d29.document.dto.DocumentImportView;
import com.zl.d29.document.dto.DocumentRegisterRequest;
import com.zl.d29.document.model.DocumentPageContent;
import com.zl.d29.document.model.DocumentStatus;
import com.zl.d29.document.model.KnowledgeDocument;
import com.zl.d29.document.service.DocumentImportService;
import com.zl.d29.document.service.DocumentPageService;
import com.zl.d29.document.service.DocumentRegistrationService;
import com.zl.d29.document.support.DocumentConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 文档入口 MockMvc 切片测试：验证统一 Result 协议与参数校验。
 */
class DocumentControllerTest {

    private DocumentRegistrationService registrationService;
    private DocumentImportService importService;
    private DocumentPageService pageService;
    private DocumentChunkService chunkService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registrationService = mock(DocumentRegistrationService.class);
        importService = mock(DocumentImportService.class);
        pageService = mock(DocumentPageService.class);
        chunkService = mock(DocumentChunkService.class);
        DocumentConverter converter = new DocumentConverter();
        DocumentController controller =
                new DocumentController(registrationService, importService, pageService, converter,
                        chunkService, new ChunkViewMapper());
        mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void registerSuccess() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId("doc-1");
        doc.setStatus(DocumentStatus.PENDING);
        doc.setVersion(1);
        when(registrationService.register(any())).thenReturn(doc);

        DocumentRegisterRequest request = new DocumentRegisterRequest();
        request.setSource("manual");
        request.setTitle("标题");

        mockMvc.perform(post("/api/v1/documents/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.documentId").value("doc-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void registerBlankSourceReturnsParamInvalid() throws Exception {
        DocumentRegisterRequest request = new DocumentRegisterRequest();
        request.setSource("");
        request.setTitle("标题");

        mockMvc.perform(post("/api/v1/documents/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001));
    }

    @Test
    void importSuccess() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId("doc-2");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setVersion(1);
        DocumentImportView importView = new DocumentImportView();
        importView.setDuplicate(false);
        importView.setDocument(new DocumentConverter().toView(doc));
        when(importService.importFile(any(), isNull(), any(), any())).thenReturn(importView);

        MockMultipartFile file = new MockMultipartFile(
                "file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/documents/import")
                        .file(file)
                        .param("source", "manual")
                        .param("title", "标题"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.document.status").value("COMPLETED"));
    }

    @Test
    void getDocument() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId("doc-1");
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setVersion(1);
        when(registrationService.getByDocumentId("doc-1")).thenReturn(doc);

        mockMvc.perform(get("/api/v1/documents/doc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value("doc-1"));
    }

    @Test
    void listPagesReturnsSourceLocation() throws Exception {
        DocumentPageContent page = new DocumentPageContent();
        page.setDocumentId("doc-1");
        page.setPageNumber(2);
        page.setContent("Second Page");
        page.setBlank(false);
        when(pageService.listPages("doc-1")).thenReturn(java.util.List.of(page));

        mockMvc.perform(get("/api/v1/documents/doc-1/pages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].pageNumber").value(2))
                .andExpect(jsonPath("$.data[0].content").value("Second Page"));
    }

    @Test
    void chunksEndpointReturnsChunkViews() throws Exception {
        Chunk chunk = new Chunk();
        chunk.setDocumentId("doc-1");
        chunk.setTitle("标题");
        chunk.setVersion(1);
        chunk.setStrategy(ChunkStrategy.FIXED_SIZE);
        chunk.setChunkIndex(0);
        chunk.setContent("chunk content");
        chunk.setLength(13);
        chunk.setCodePointCount(13);
        chunk.setGlobalStart(0);
        chunk.setGlobalEnd(13);
        chunk.setSources(java.util.List.of(
                new com.zl.d29.document.chunk.model.ChunkSource(1, 0, 0, 13)));

        ChunkResult result = new ChunkResult();
        result.setFixedChunks(java.util.List.of(chunk));
        result.setStructureChunks(java.util.List.of());
        when(pageService.listPages("doc-1")).thenReturn(java.util.List.of(new DocumentPageContent()));
        when(chunkService.process(any())).thenReturn(result);

        mockMvc.perform(get("/api/v1/documents/doc-1/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].content").value("chunk content"));
    }
}
