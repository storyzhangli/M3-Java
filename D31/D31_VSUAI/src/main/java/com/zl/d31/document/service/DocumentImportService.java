package com.zl.d31.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d31.document.dto.DocumentImportView;
import com.zl.d31.document.exception.DocumentParseException;
import com.zl.d31.document.exception.DocumentValidationException;
import com.zl.d31.document.mapper.KnowledgeDocumentMapper;
import com.zl.d31.document.model.DocumentPageContent;
import com.zl.d31.document.model.DocumentStatus;
import com.zl.d31.document.model.KnowledgeDocument;
import com.zl.d31.document.po.KnowledgeDocumentPO;
import com.zl.d31.document.service.DocumentPageService;
import com.zl.d31.document.support.BasicDocumentParser;
import com.zl.d31.document.support.DocumentConverter;
import com.zl.d31.document.support.FileUploadValidator;
import com.zl.d31.document.support.PdfDocumentParser;
import com.zl.d31.document.support.Sha256ContentHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档导入服务：服务端校验、contentHash 去重、状态机驱动与解析。
 *
 * <p>按文件类型路由：PDF 走 {@link PdfDocumentParser} 并持久化页面级来源定位，
 * 文本走 {@link BasicDocumentParser}。同步处理，保证从 PROCESSING 收敛到
 * COMPLETED 或 FAILED，不吞异常、不伪造成功。</p>
 */
@Service
public class DocumentImportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImportService.class);

    private final FileUploadValidator validator;
    private final Sha256ContentHasher hasher;
    private final BasicDocumentParser parser;
    private final PdfDocumentParser pdfParser;
    private final DocumentPageService pageService;
    private final KnowledgeDocumentMapper mapper;
    private final DocumentConverter converter;

    public DocumentImportService(FileUploadValidator validator,
                                 Sha256ContentHasher hasher,
                                 BasicDocumentParser parser,
                                 PdfDocumentParser pdfParser,
                                 DocumentPageService pageService,
                                 KnowledgeDocumentMapper mapper,
                                 DocumentConverter converter) {
        this.validator = validator;
        this.hasher = hasher;
        this.parser = parser;
        this.pdfParser = pdfParser;
        this.pageService = pageService;
        this.mapper = mapper;
        this.converter = converter;
    }

    /**
     * 导入文件。documentId 非空时关联已登记文档，否则用 source/title 新建。
     */
    @Transactional
    public DocumentImportView importFile(MultipartFile file, String documentId, String source, String title) {
        byte[] bytes = validator.validate(file);
        String contentHash = hasher.hash(bytes);
        LocalDateTime now = LocalDateTime.now();

        KnowledgeDocument byHash = findByHash(contentHash);
        // FAILED 文档允许同内容重新导入重试，其余状态命中重复直接返回。
        if (byHash != null && byHash.getStatus() != DocumentStatus.FAILED) {
            return buildImportView(true, byHash);
        }

        KnowledgeDocument doc;
        if (byHash != null) {
            doc = byHash;
            doc.setStatus(DocumentStatus.PROCESSING);
            doc.setUpdatedAt(now);
        } else {
            doc = resolveDocument(documentId, source, title);
        }
        doc.setContentHash(contentHash);
        doc.setContentLength((long) bytes.length);
        doc.setErrorCode("");
        doc.setErrorMessage("");

        DocumentImportView concurrent = persistProcessing(doc);
        if (concurrent != null) {
            return concurrent;
        }

        if (isPdf(file)) {
            handlePdf(bytes, doc, now);
        } else {
            handleText(bytes, doc);
        }

        doc.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(converter.toPO(doc));
        return buildImportView(false, doc);
    }

    private void handlePdf(byte[] bytes, KnowledgeDocument doc, LocalDateTime now) {
        try {
            List<DocumentPageContent> pages = pdfParser.parse(bytes);
            preparePages(doc, pages, now);
            pageService.replacePages(pages);
            boolean anyUsable = pages.stream().anyMatch(p -> !Boolean.TRUE.equals(p.getBlank()));
            if (anyUsable) {
                doc.setStatus(DocumentStatus.COMPLETED);
            } else {
                // 全部页面无可用文字：扫描型/空文本，明确识别能力边界，不伪造成功。
                markFailed(doc, "NO_EXTRACTABLE_TEXT", "PDF 无可用提取文本，可能为扫描件，需要 OCR");
            }
        } catch (DocumentParseException e) {
            markFailed(doc, "PARSE_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            log.error("PDF 处理发生未预期异常", e);
            markFailed(doc, "PROCESSING_FAILED", "文档处理失败，请稍后重试");
        }
    }

    private void handleText(byte[] bytes, KnowledgeDocument doc) {
        try {
            parser.parse(bytes);
            doc.setStatus(DocumentStatus.COMPLETED);
        } catch (DocumentParseException e) {
            markFailed(doc, "PARSE_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            log.error("文档处理发生未预期异常", e);
            markFailed(doc, "PROCESSING_FAILED", "文档处理失败，请稍后重试");
        }
    }

    private void preparePages(KnowledgeDocument doc, List<DocumentPageContent> pages, LocalDateTime now) {
        for (DocumentPageContent page : pages) {
            page.setDocumentId(doc.getDocumentId());
            page.setSource(doc.getSource());
            page.setTitle(doc.getTitle());
            page.setVersion(doc.getVersion());
            page.setCreatedAt(now);
            page.setUpdatedAt(now);
        }
    }

    private boolean isPdf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && "pdf".equalsIgnoreCase(name.substring(dot + 1));
    }

    private KnowledgeDocument findByHash(String contentHash) {
        KnowledgeDocumentPO po = mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("content_hash", contentHash));
        return converter.toDomain(po);
    }

    private KnowledgeDocument resolveDocument(String documentId, String source, String title) {
        LocalDateTime now = LocalDateTime.now();
        if (documentId != null && !documentId.isBlank()) {
            KnowledgeDocument existing = findByDocumentId(documentId.trim());
            existing.setStatus(DocumentStatus.PROCESSING);
            existing.setUpdatedAt(now);
            return existing;
        }
        if (source == null || source.isBlank() || title == null || title.isBlank()) {
            throw new DocumentValidationException("新建导入必须提供文档来源与标题");
        }
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId(UUID.randomUUID().toString().replace("-", ""));
        doc.setSource(source.trim());
        doc.setTitle(title.trim());
        doc.setVersion(1);
        doc.setStatus(DocumentStatus.PROCESSING);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        return doc;
    }

    private KnowledgeDocument findByDocumentId(String documentId) {
        KnowledgeDocumentPO po = mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
        if (po == null) {
            throw new DocumentValidationException("文档不存在: " + documentId);
        }
        return converter.toDomain(po);
    }

    private DocumentImportView persistProcessing(KnowledgeDocument doc) {
        if (doc.getId() == null) {
            try {
                mapper.insert(converter.toPO(doc));
            } catch (DuplicateKeyException e) {
                // 并发重复：唯一约束兜底，在异常到达事务拦截器前于方法内捕获，
                // 事务正常提交，既有行不受影响，按重复响应返回。
                KnowledgeDocument existing = findByHash(doc.getContentHash());
                if (existing != null) {
                    return buildImportView(true, existing);
                }
                throw e;
            }
            return null;
        }
        mapper.updateById(converter.toPO(doc));
        return null;
    }

    private void markFailed(KnowledgeDocument doc, String errorCode, String errorMessage) {
        doc.setStatus(DocumentStatus.FAILED);
        doc.setErrorCode(errorCode);
        doc.setErrorMessage(errorMessage == null ? "" : errorMessage);
    }

    private DocumentImportView buildImportView(boolean duplicate, KnowledgeDocument doc) {
        DocumentImportView view = new DocumentImportView();
        view.setDuplicate(duplicate);
        view.setDocument(converter.toView(doc));
        return view;
    }
}
