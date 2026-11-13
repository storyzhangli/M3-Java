package com.zl.d30.document;

import com.zl.d30.document.config.DocumentImportProperties;
import com.zl.d30.document.dto.DocumentRegisterRequest;
import com.zl.d30.document.exception.DocumentValidationException;
import com.zl.d30.document.mapper.KnowledgeDocumentMapper;
import com.zl.d30.document.model.DocumentStatus;
import com.zl.d30.document.model.KnowledgeDocument;
import com.zl.d30.document.service.DocumentRegistrationService;
import com.zl.d30.document.support.DocumentConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 文档登记服务测试。
 */
class DocumentRegistrationServiceTest {

    private KnowledgeDocumentMapper mapper;
    private DocumentRegistrationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(KnowledgeDocumentMapper.class);
        DocumentConverter converter = new DocumentConverter();
        service = new DocumentRegistrationService(mapper, converter, new DocumentImportProperties());
    }

    @Test
    void registerCreatesPending() {
        DocumentRegisterRequest request = new DocumentRegisterRequest();
        request.setSource("manual");
        request.setTitle("设备手册");
        when(mapper.insert(any(com.zl.d30.document.po.KnowledgeDocumentPO.class))).thenReturn(1);

        KnowledgeDocument doc = service.register(request);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(doc.getVersion()).isEqualTo(1);
        assertThat(doc.getDocumentId()).isNotBlank();
        verify(mapper).insert(any(com.zl.d30.document.po.KnowledgeDocumentPO.class));
    }

    @Test
    void registerWithExternalIdUsesIt() {
        DocumentRegisterRequest request = new DocumentRegisterRequest();
        request.setSource("manual");
        request.setTitle("title");
        request.setDocumentId("ext-123");
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(com.zl.d30.document.po.KnowledgeDocumentPO.class))).thenReturn(1);

        KnowledgeDocument doc = service.register(request);

        assertThat(doc.getDocumentId()).isEqualTo("ext-123");
    }

    @Test
    void registerDuplicateExternalIdRejected() {
        DocumentRegisterRequest request = new DocumentRegisterRequest();
        request.setSource("manual");
        request.setTitle("title");
        request.setDocumentId("ext-123");
        when(mapper.selectOne(any())).thenReturn(new com.zl.d30.document.po.KnowledgeDocumentPO());

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).insert(any(com.zl.d30.document.po.KnowledgeDocumentPO.class));
    }

    @Test
    void getMissingDocumentThrows() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.getByDocumentId("nope"))
                .isInstanceOf(DocumentValidationException.class);
    }

    @Test
    void listAllReturnsDocuments() {
        com.zl.d30.document.po.KnowledgeDocumentPO po = buildPO("d1", "COMPLETED");
        when(mapper.selectList(any())).thenReturn(List.of(po));
        assertThat(service.listAll()).hasSize(1);
    }

    private com.zl.d30.document.po.KnowledgeDocumentPO buildPO(String documentId, String status) {
        com.zl.d30.document.po.KnowledgeDocumentPO po =
                new com.zl.d30.document.po.KnowledgeDocumentPO();
        po.setDocumentId(documentId);
        po.setStatus(status);
        po.setVersion(1);
        return po;
    }
}
