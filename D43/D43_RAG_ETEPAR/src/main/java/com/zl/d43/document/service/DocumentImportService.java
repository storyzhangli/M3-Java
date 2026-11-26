package com.zl.d43.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d43.document.dto.DocumentImportView;
import com.zl.d43.document.exception.DocumentParseException;
import com.zl.d43.document.exception.DocumentValidationException;
import com.zl.d43.document.mapper.KnowledgeDocumentMapper;
import com.zl.d43.document.model.DocumentPageContent;
import com.zl.d43.document.model.DocumentStatus;
import com.zl.d43.document.model.KnowledgeDocument;
import com.zl.d43.document.po.KnowledgeDocumentPO;
import com.zl.d43.document.service.DocumentPageService;
import com.zl.d43.document.support.*;
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

    /** P2 追加：表格结构化分支（REQUIRES_NEW 独立事务，失败不连坐正文）。 */
    private final com.zl.d43.document.table.service.TableRecordService tableRecordService;

    public DocumentImportService(FileUploadValidator validator,
                                 Sha256ContentHasher hasher,
                                 BasicDocumentParser parser,
                                 PdfDocumentParser pdfParser,
                                 DocumentPageService pageService,
                                 KnowledgeDocumentMapper mapper,
                                 DocumentConverter converter,
                                 com.zl.d43.document.table.service.TableRecordService tableRecordService) {
        this.validator = validator;
        this.hasher = hasher;
        this.parser = parser;
        this.pdfParser = pdfParser;
        this.pageService = pageService;
        this.mapper = mapper;
        this.converter = converter;
        this.tableRecordService = tableRecordService;
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
                // 表格钩子（独立分支）：REQUIRES_NEW 独立事务 + 静默降级，
                // 失败只记日志（正文 COMPLETED 不受影响，缺表由 TABLE_ORPHAN 对账暴露）。
                extractTablesQuietly(doc.getDocumentId(), doc.getVersion(), pages, bytes, true);
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
            String text = parser.parse(bytes);
            // 最小增量：txt/md 与 PDF 共用页表链路，整篇文本作为第 1 页写入，
            // 使 indexFromPages（重新切分→Embedding→入库）对文本文档同样可用。
            DocumentPageContent page = new DocumentPageContent();
            page.setPageNumber(1);
            page.setContent(text);
            page.setContentLength(text.length());
            page.setBlank(false);
            LocalDateTime now = LocalDateTime.now();
            preparePages(doc, List.of(page), now);
            pageService.replacePages(List.of(page));
            doc.setStatus(DocumentStatus.COMPLETED);
            // 表格钩子：md 管道表/tab 表文本重放解析（同 PDF 分支的解耦语义）。
            extractTablesQuietly(doc.getDocumentId(), doc.getVersion(), List.of(page), null, false);
        } catch (DocumentParseException e) {
            markFailed(doc, "PARSE_FAILED", e.getMessage());
        } catch (RuntimeException e) {
            log.error("文档处理发生未预期异常", e);
            markFailed(doc, "PROCESSING_FAILED", "文档处理失败，请稍后重试");
        }
    }

    /**
     * 表格分支调用包装：任何异常都不向上抛出（分支隔离）。
     * 注意 extractAndPersist 为 REQUIRES_NEW——其失败整体回滚表事务，
     * 外层正文事务不因 rollback-only 连坐（这正是选择 REQUIRES_NEW 的原因）。
     */
    private void extractTablesQuietly(String documentId, int version,
                                      List<DocumentPageContent> pages, byte[] pdfBytes, boolean pdf) {
        try {
            var result = tableRecordService.extractAndPersist(documentId, version, pages, pdfBytes, pdf);
            log.info("表格分支完成 document={} tables={} rows={} skipped={}",
                    documentId, result.tableCount(), result.rowCount(), result.skipped());
        } catch (Exception e) {
            log.error("表格分支失败（正文不受影响；TABLE_ORPHAN 对账/重抽取兜底） document={} type={}",
                    documentId, e.getClass().getSimpleName(), e);
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
