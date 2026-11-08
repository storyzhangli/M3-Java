package com.zl.d26.document;

import com.zl.d26.document.config.DocumentImportProperties;
import com.zl.d26.document.dto.DocumentImportView;
import com.zl.d26.document.mapper.KnowledgeDocumentMapper;
import com.zl.d26.document.po.KnowledgeDocumentPO;
import com.zl.d26.document.support.BasicDocumentParser;
import com.zl.d26.document.support.DocumentConverter;
import com.zl.d26.document.support.FileUploadValidator;
import com.zl.d26.document.support.Sha256ContentHasher;
import com.zl.d26.document.service.DocumentImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档导入服务测试：状态机、重复、失败、更新时间与并发。
 * 文件校验本身在 {@link FileUploadValidatorTest} 用真实字节验证，这里聚焦状态机。
 */
class DocumentImportServiceTest {

    private FileUploadValidator validator;
    private Sha256ContentHasher hasher;
    private KnowledgeDocumentMapper mapper;
    private DocumentImportService service;

    @BeforeEach
    void setUp() {
        validator = mock(FileUploadValidator.class);
        hasher = mock(Sha256ContentHasher.class);
        mapper = mock(KnowledgeDocumentMapper.class);
        BasicDocumentParser parser = new BasicDocumentParser(new DocumentImportProperties());
        DocumentConverter converter = new DocumentConverter();
        service = new DocumentImportService(validator, hasher, parser, mapper, converter);
    }

    @Test
    void newImportCompletes() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash");
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(KnowledgeDocumentPO.class))).thenReturn(1);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                null, "manual", "标题");

        assertThat(view.isDuplicate()).isFalse();
        assertThat(view.getDocument().getStatus()).isEqualTo("COMPLETED");
        verify(mapper).insert(any(KnowledgeDocumentPO.class));
        verify(mapper).updateById(any(KnowledgeDocumentPO.class));
    }

    @Test
    void duplicateHashReturnsExistingWithoutSecondFlow() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash");
        KnowledgeDocumentPO existing = buildPO("doc-1", "COMPLETED");
        when(mapper.selectOne(any())).thenReturn(existing);

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                null, "manual", "标题");

        assertThat(view.isDuplicate()).isTrue();
        assertThat(view.getDocument().getDocumentId()).isEqualTo("doc-1");
        verify(mapper, never()).insert(any(KnowledgeDocumentPO.class));
        verify(mapper, never()).updateById(any(KnowledgeDocumentPO.class));
    }

    @Test
    void parseFailureMarksFailed() {
        byte[] bytes = " \n\t".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash-fail");
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(KnowledgeDocumentPO.class))).thenReturn(1);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                null, "manual", "标题");

        assertThat(view.getDocument().getStatus()).isEqualTo("FAILED");
        assertThat(view.getDocument().getErrorCode()).isEqualTo("PARSE_FAILED");
    }

    @Test
    void unexpectedRuntimeMarksFailed() {
        BasicDocumentParser failingParser = mock(BasicDocumentParser.class);
        when(failingParser.parse(any())).thenThrow(new RuntimeException("boom"));
        DocumentImportService s = new DocumentImportService(
                validator, hasher, failingParser, mapper, new DocumentConverter());

        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash-x");
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(KnowledgeDocumentPO.class))).thenReturn(1);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);

        DocumentImportView view = s.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                null, "manual", "标题");

        assertThat(view.getDocument().getStatus()).isEqualTo("FAILED");
        assertThat(view.getDocument().getErrorCode()).isEqualTo("PROCESSING_FAILED");
    }

    @Test
    void statusUpdateTimeAdvances() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash");
        LocalDateTime old = LocalDateTime.now().minusDays(1);
        KnowledgeDocumentPO preRegistered = buildPO("doc-2", "PENDING");
        preRegistered.setId(1L);
        preRegistered.setCreatedAt(old);
        preRegistered.setUpdatedAt(old);
        // 第一次 selectOne：按 hash 查无；第二次：按 documentId 查到已登记行
        when(mapper.selectOne(any())).thenReturn(null, preRegistered);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);

        service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                "doc-2", null, null);

        ArgumentCaptor<KnowledgeDocumentPO> captor = ArgumentCaptor.forClass(KnowledgeDocumentPO.class);
        verify(mapper, org.mockito.Mockito.atLeastOnce()).updateById(captor.capture());
        KnowledgeDocumentPO updated = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        assertThat(updated.getUpdatedAt()).isAfter(old);
    }

    @Test
    void concurrentDuplicateHandledByUniqueConstraint() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash");
        KnowledgeDocumentPO existing = buildPO("doc-3", "COMPLETED");
        // 首次按 hash 查无（两线程都未提交）；insert 触发唯一约束；回查命中既有
        when(mapper.selectOne(any())).thenReturn(null, existing);
        when(mapper.insert(any(KnowledgeDocumentPO.class))).thenThrow(new DuplicateKeyException("uk_content_hash"));

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                null, "manual", "标题");

        assertThat(view.isDuplicate()).isTrue();
        assertThat(view.getDocument().getDocumentId()).isEqualTo("doc-3");
        verify(mapper, never()).updateById(any(KnowledgeDocumentPO.class));
    }

    private void stubCommon(byte[] bytes, String hash) {
        when(validator.validate(any())).thenReturn(bytes);
        when(hasher.hash(any(byte[].class))).thenReturn(hash);
    }

    private KnowledgeDocumentPO buildPO(String documentId, String status) {
        KnowledgeDocumentPO po = new KnowledgeDocumentPO();
        po.setDocumentId(documentId);
        po.setStatus(status);
        po.setVersion(1);
        po.setSource("manual");
        po.setTitle("标题");
        po.setContentHash("hash");
        po.setContentLength(5L);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }
}
