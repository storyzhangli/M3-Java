package com.zl.d27.document.support;

import com.zl.d27.document.dto.DocumentView;
import com.zl.d27.document.model.DocumentStatus;
import com.zl.d27.document.model.KnowledgeDocument;
import com.zl.d27.document.po.KnowledgeDocumentPO;
import org.springframework.stereotype.Component;

/**
 * 文档对象转换器：领域模型、PO、视图之间的转换集中在此，服务层不散落映射代码。
 */
@Component
public class DocumentConverter {

    public KnowledgeDocument toDomain(KnowledgeDocumentPO po) {
        if (po == null) {
            return null;
        }
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(po.getId());
        doc.setDocumentId(po.getDocumentId());
        doc.setSource(po.getSource());
        doc.setTitle(po.getTitle());
        doc.setVersion(po.getVersion());
        doc.setStatus(DocumentStatus.fromName(po.getStatus()));
        doc.setContentHash(po.getContentHash());
        doc.setContentLength(po.getContentLength());
        doc.setErrorCode(po.getErrorCode());
        doc.setErrorMessage(po.getErrorMessage());
        doc.setCreatedAt(po.getCreatedAt());
        doc.setUpdatedAt(po.getUpdatedAt());
        return doc;
    }

    public KnowledgeDocumentPO toPO(KnowledgeDocument doc) {
        KnowledgeDocumentPO po = new KnowledgeDocumentPO();
        po.setId(doc.getId());
        po.setDocumentId(doc.getDocumentId());
        po.setSource(doc.getSource());
        po.setTitle(doc.getTitle());
        po.setVersion(doc.getVersion());
        po.setStatus(doc.getStatus().name());
        po.setContentHash(doc.getContentHash());
        po.setContentLength(doc.getContentLength());
        po.setErrorCode(doc.getErrorCode());
        po.setErrorMessage(doc.getErrorMessage());
        po.setCreatedAt(doc.getCreatedAt());
        po.setUpdatedAt(doc.getUpdatedAt());
        return po;
    }

    public DocumentView toView(KnowledgeDocument doc) {
        DocumentView view = new DocumentView();
        view.setDocumentId(doc.getDocumentId());
        view.setSource(doc.getSource());
        view.setTitle(doc.getTitle());
        view.setVersion(doc.getVersion());
        view.setStatus(doc.getStatus().name());
        view.setContentLength(doc.getContentLength());
        view.setErrorCode(doc.getErrorCode());
        view.setErrorMessage(doc.getErrorMessage());
        view.setCreatedAt(doc.getCreatedAt());
        view.setUpdatedAt(doc.getUpdatedAt());
        return view;
    }
}
