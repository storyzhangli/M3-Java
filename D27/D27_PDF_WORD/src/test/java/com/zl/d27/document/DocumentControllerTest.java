package com.zl.d27.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d27.common.GlobalExceptionHandler;
import com.zl.d27.document.controller.DocumentController;
import com.zl.d27.document.dto.DocumentImportView;
import com.zl.d27.document.dto.DocumentRegisterRequest;
import com.zl.d27.document.model.DocumentStatus;
import com.zl.d27.document.model.KnowledgeDocument;
import com.zl.d27.document.service.DocumentImportService;
import com.zl.d27.document.service.DocumentRegistrationService;
import com.zl.d27.document.support.DocumentConverter;
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
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registrationService = mock(DocumentRegistrationService.class);
        importService = mock(DocumentImportService.class);
        DocumentConverter converter = new DocumentConverter();
        DocumentController controller =
                new DocumentController(registrationService, importService, converter);
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
}
