package com.zl.d28.document.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d28.document.mapper.DocumentPageContentMapper;
import com.zl.d28.document.model.DocumentPageContent;
import com.zl.d28.document.po.DocumentPageContentPO;
import com.zl.d28.document.support.DocumentConverter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档页面级提取内容服务：负责页面结果的替换写入与来源定位查询。
 *
 * <p>写入在导入服务的事务内被调用；重新解析时先按 documentId 删除旧页面行，
 * 再插入新行，保证页面序号与最新解析一致。</p>
 */
@Service
public class DocumentPageService {

    private final DocumentPageContentMapper mapper;
    private final DocumentConverter converter;

    public DocumentPageService(DocumentPageContentMapper mapper, DocumentConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    /**
     * 替换某文档的全部页面行（同事务）。
     */
    public void replacePages(List<DocumentPageContent> pages) {
        if (pages == null || pages.isEmpty()) {
            return;
        }
        String documentId = pages.get(0).getDocumentId();
        mapper.delete(new QueryWrapper<DocumentPageContentPO>().eq("document_id", documentId));
        for (DocumentPageContent page : pages) {
            mapper.insert(converter.toPagePO(page));
        }
    }

    /**
     * 按物理页码顺序查询文档的全部页面。
     */
    public List<DocumentPageContent> listPages(String documentId) {
        return mapper.selectList(new QueryWrapper<DocumentPageContentPO>()
                        .eq("document_id", documentId)
                        .orderByAsc("page_number")).stream()
                .map(converter::toPageDomain)
                .toList();
    }

    /**
     * 查询单个物理页。
     */
    public DocumentPageContent getPage(String documentId, int pageNumber) {
        return converter.toPageDomain(mapper.selectOne(
                new QueryWrapper<DocumentPageContentPO>()
                        .eq("document_id", documentId)
                        .eq("page_number", pageNumber)));
    }
}
