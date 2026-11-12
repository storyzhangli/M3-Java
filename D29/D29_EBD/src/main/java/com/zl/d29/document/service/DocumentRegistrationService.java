package com.zl.d29.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d29.document.config.DocumentImportProperties;
import com.zl.d29.document.dto.DocumentRegisterRequest;
import com.zl.d29.document.exception.DocumentValidationException;
import com.zl.d29.document.mapper.KnowledgeDocumentMapper;
import com.zl.d29.document.model.DocumentStatus;
import com.zl.d29.document.model.KnowledgeDocument;
import com.zl.d29.document.po.KnowledgeDocumentPO;
import com.zl.d29.document.support.DocumentConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档登记服务：只处理元数据，不接触文件字节。
 *
 * <p>登记后文档处于 {@code PENDING}，等待文件导入驱动后续状态。</p>
 */
@Service
public class DocumentRegistrationService {

    private final KnowledgeDocumentMapper mapper;
    private final DocumentConverter converter;
    private final DocumentImportProperties properties;

    public DocumentRegistrationService(KnowledgeDocumentMapper mapper,
                                       DocumentConverter converter,
                                       DocumentImportProperties properties) {
        this.mapper = mapper;
        this.converter = converter;
        this.properties = properties;
    }

    /**
     * 登记新文档，返回领域模型（PENDING）。
     */
    @Transactional
    public KnowledgeDocument register(DocumentRegisterRequest request) {
        String documentId = resolveDocumentId(request.getDocumentId());
        LocalDateTime now = LocalDateTime.now();

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocumentId(documentId);
        doc.setSource(request.getSource().trim());
        doc.setTitle(safeTitle(request.getTitle()));
        doc.setVersion(1);
        doc.setStatus(DocumentStatus.PENDING);
        doc.setContentHash(null);
        doc.setContentLength(0L);
        doc.setErrorCode("");
        doc.setErrorMessage("");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);

        mapper.insert(converter.toPO(doc));
        return doc;
    }

    public KnowledgeDocument getByDocumentId(String documentId) {
        KnowledgeDocumentPO po = mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
        if (po == null) {
            throw new DocumentValidationException("文档不存在: " + documentId);
        }
        return converter.toDomain(po);
    }

    public List<KnowledgeDocument> listAll() {
        return mapper.selectList(new QueryWrapper<KnowledgeDocumentPO>()
                        .orderByDesc("updated_at"))
                .stream().map(converter::toDomain).toList();
    }

    private String resolveDocumentId(String external) {
        if (external != null && !external.isBlank()) {
            String id = external.trim();
            KnowledgeDocumentPO existing = mapper.selectOne(
                    new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", id));
            if (existing != null) {
                throw new DocumentValidationException("文档标识已存在: " + id);
            }
            return id;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String safeTitle(String title) {
        String trimmed = title.trim();
        int max = properties.getTitleMaxLength();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
