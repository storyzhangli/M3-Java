package com.zl.d29.document.support;

import com.zl.d29.document.dto.DocumentPageView;
import com.zl.d29.document.dto.DocumentView;
import com.zl.d29.document.model.DocumentPageContent;
import com.zl.d29.document.model.DocumentStatus;
import com.zl.d29.document.model.KnowledgeDocument;
import com.zl.d29.document.po.DocumentPageContentPO;
import com.zl.d29.document.po.KnowledgeDocumentPO;
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

    public DocumentPageContent toPageDomain(DocumentPageContentPO po) {
        if (po == null) {
            return null;
        }
        DocumentPageContent page = new DocumentPageContent();
        page.setId(po.getId());
        page.setDocumentId(po.getDocumentId());
        page.setSource(po.getSource());
        page.setTitle(po.getTitle());
        page.setVersion(po.getVersion());
        page.setPageNumber(po.getPageNumber());
        page.setContent(po.getContent());
        page.setContentLength(po.getContentLength());
        page.setBlank(po.getBlank() != null && po.getBlank() == 1);
        page.setCreatedAt(po.getCreatedAt());
        page.setUpdatedAt(po.getUpdatedAt());
        return page;
    }

    public DocumentPageContentPO toPagePO(DocumentPageContent page) {
        DocumentPageContentPO po = new DocumentPageContentPO();
        po.setId(page.getId());
        po.setDocumentId(page.getDocumentId());
        po.setSource(page.getSource());
        po.setTitle(page.getTitle());
        po.setVersion(page.getVersion());
        po.setPageNumber(page.getPageNumber());
        po.setContent(page.getContent());
        po.setContentLength(page.getContentLength());
        po.setBlank(Boolean.TRUE.equals(page.getBlank()) ? 1 : 0);
        po.setCreatedAt(page.getCreatedAt());
        po.setUpdatedAt(page.getUpdatedAt());
        return po;
    }

    public DocumentPageView toPageView(DocumentPageContent page) {
        DocumentPageView view = new DocumentPageView();
        view.setDocumentId(page.getDocumentId());
        view.setSource(page.getSource());
        view.setTitle(page.getTitle());
        view.setVersion(page.getVersion());
        view.setPageNumber(page.getPageNumber());
        view.setContent(page.getContent());
        view.setContentLength(page.getContentLength());
        view.setBlank(page.getBlank());
        view.setUpdatedAt(page.getUpdatedAt());
        return view;
    }
}
