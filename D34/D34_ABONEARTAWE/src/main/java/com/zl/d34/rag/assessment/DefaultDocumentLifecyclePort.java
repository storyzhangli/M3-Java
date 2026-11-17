package com.zl.d34.rag.assessment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d34.document.mapper.KnowledgeDocumentMapper;
import com.zl.d34.document.model.DocumentLifecycleStatus;
import com.zl.d34.document.po.KnowledgeDocumentPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于 MySQL 的文档生命周期解析默认实现。
 *
 * <p>查询失败或文档不存在时回退为 {@link DocumentLifecycleStatus#ACTIVE}，
 * 避免因解析故障造成过度阻断；异常仅记录类型，不回显连接细节。</p>
 */
@Slf4j
@Component
public class DefaultDocumentLifecyclePort implements DocumentLifecyclePort {

    private final KnowledgeDocumentMapper mapper;

    public DefaultDocumentLifecyclePort(KnowledgeDocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public DocumentLifecycleStatus resolve(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return DocumentLifecycleStatus.ACTIVE;
        }
        try {
            KnowledgeDocumentPO po = mapper.selectOne(
                    new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
            if (po == null) {
                log.info("生命周期解析未找到文档，回退 ACTIVE: documentId={}", documentId);
                return DocumentLifecycleStatus.ACTIVE;
            }
            return DocumentLifecycleStatus.fromName(po.getLifecycleStatus());
        } catch (RuntimeException ex) {
            log.error("生命周期解析异常，回退 ACTIVE，异常类型: {}", ex.getClass().getSimpleName());
            return DocumentLifecycleStatus.ACTIVE;
        }
    }
}
