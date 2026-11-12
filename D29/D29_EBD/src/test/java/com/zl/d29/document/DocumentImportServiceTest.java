package com.zl.d29.document;

import com.zl.d29.document.config.DocumentImportProperties;
import com.zl.d29.document.dto.DocumentImportView;
import com.zl.d29.document.mapper.KnowledgeDocumentMapper;
import com.zl.d29.document.model.DocumentPageContent;
import com.zl.d29.document.po.KnowledgeDocumentPO;
import com.zl.d29.document.service.DocumentImportService;
import com.zl.d29.document.service.DocumentPageService;
import com.zl.d29.document.support.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 文档导入服务测试：状态机、重复、失败、更新时间、并发与 PDF 路由。
 * 文件校验本身在 {@link FileUploadValidatorTest} 用真实字节验证，这里聚焦状态机。
 */
class DocumentImportServiceTest {

    private FileUploadValidator validator;
    private Sha256ContentHasher hasher;
    private PdfDocumentParser pdfParser;
    private DocumentPageService pageService;
    private KnowledgeDocumentMapper mapper;
    private DocumentImportService service;

    @BeforeEach
    void setUp() {
        validator = mock(FileUploadValidator.class);
        hasher = mock(Sha256ContentHasher.class);
        pdfParser = mock(PdfDocumentParser.class);
        pageService = mock(DocumentPageService.class);
        mapper = mock(KnowledgeDocumentMapper.class);
        BasicDocumentParser parser = new BasicDocumentParser(new DocumentImportProperties());
        DocumentConverter converter = new DocumentConverter();
        service = new DocumentImportService(validator, hasher, parser, pdfParser,
                pageService, mapper, converter);
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
        verifyNoInteractions(pageService);
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
                validator, hasher, failingParser, pdfParser, pageService,
                mapper, new DocumentConverter());

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
        when(mapper.selectOne(any())).thenReturn(null, preRegistered);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);

        service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                "doc-2", null, null);

        ArgumentCaptor<KnowledgeDocumentPO> captor = ArgumentCaptor.forClass(KnowledgeDocumentPO.class);
        verify(mapper, atLeastOnce()).updateById(captor.capture());
        KnowledgeDocumentPO updated = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
        assertThat(updated.getUpdatedAt()).isAfter(old);
    }

    @Test
    void concurrentDuplicateHandledByUniqueConstraint() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash");
        KnowledgeDocumentPO existing = buildPO("doc-3", "COMPLETED");
        when(mapper.selectOne(any())).thenReturn(null, existing);
        when(mapper.insert(any(KnowledgeDocumentPO.class)))
                .thenThrow(new DuplicateKeyException("uk_content_hash"));

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                null, "manual", "标题");

        assertThat(view.isDuplicate()).isTrue();
        assertThat(view.getDocument().getDocumentId()).isEqualTo("doc-3");
        verify(mapper, never()).updateById(any(KnowledgeDocumentPO.class));
    }

    @Test
    void pdfImportCompletesAndPersistsPages() {
        byte[] bytes = "%PDF-1.7 fake".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "pdf-hash");
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(KnowledgeDocumentPO.class))).thenReturn(1);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);
        DocumentPageContent p1 = page(1, "First Page", false);
        DocumentPageContent p2 = page(2, "Second Page", false);
        when(pdfParser.parse(bytes)).thenReturn(List.of(p1, p2));

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "manual.pdf", "application/pdf", bytes),
                null, "manual", "手册");

        assertThat(view.getDocument().getStatus()).isEqualTo("COMPLETED");
        verify(pageService).replacePages(anyList());
        // 页面被写入来源元数据
        ArgumentCaptor<List<DocumentPageContent>> captor = ArgumentCaptor.forClass(List.class);
        verify(pageService).replacePages(captor.capture());
        DocumentPageContent saved = captor.getValue().get(0);
        assertThat(saved.getDocumentId()).isNotBlank();
        assertThat(saved.getPageNumber()).isEqualTo(1);
    }

    @Test
    void pdfAllBlankPagesMarksNoExtractableText() {
        byte[] bytes = "%PDF scanned".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "scan-hash");
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(KnowledgeDocumentPO.class))).thenReturn(1);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);
        when(pdfParser.parse(bytes)).thenReturn(List.of(page(1, "   ", true), page(2, "\n", true)));

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "scan.pdf", "application/pdf", bytes),
                null, "manual", "扫描件");

        assertThat(view.getDocument().getStatus()).isEqualTo("FAILED");
        assertThat(view.getDocument().getErrorCode()).isEqualTo("NO_EXTRACTABLE_TEXT");
        // 页面定位仍保留
        verify(pageService).replacePages(anyList());
    }

    @Test
    void failedDocumentRetryReParsesInsteadOfDuplicate() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        stubCommon(bytes, "hash");
        KnowledgeDocumentPO failed = buildPO("doc-9", "FAILED");
        failed.setId(7L);
        when(mapper.selectOne(any())).thenReturn(failed);
        when(mapper.updateById(any(KnowledgeDocumentPO.class))).thenReturn(1);

        DocumentImportView view = service.importFile(
                new MockMultipartFile("file", "a.txt", "text/plain", bytes),
                null, "manual", "标题");

        assertThat(view.isDuplicate()).isFalse();
        assertThat(view.getDocument().getStatus()).isEqualTo("COMPLETED");
    }

    private DocumentPageContent page(int number, String text, boolean blank) {
        DocumentPageContent page = new DocumentPageContent();
        page.setPageNumber(number);
        page.setContent(text);
        page.setContentLength(text.length());
        page.setBlank(blank);
        return page;
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
