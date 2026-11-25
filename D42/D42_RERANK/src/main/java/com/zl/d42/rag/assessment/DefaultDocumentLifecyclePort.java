package com.zl.d42.rag.assessment;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d42.document.mapper.KnowledgeDocumentMapper;
import com.zl.d42.document.model.DocumentLifecycleStatus;
import com.zl.d42.document.po.KnowledgeDocumentPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 基于 MySQL 的文档生命周期解析默认实现（Fail-closed）。
 *
 * <p>明确区分四类结果，均不默认 {@link DocumentLifecycleStatus#ACTIVE}：</p>
 * <ul>
 *   <li>查询无行 → {@link DocumentLifecycleStatus#NOT_FOUND}（业务上不存在）；</li>
 *   <li>存储状态为空/无法识别 → {@link DocumentLifecycleStatus#UNKNOWN}；</li>
 *   <li>查询抛出异常（基础设施故障）→ {@link DocumentLifecycleStatus#LOOKUP_FAILED}；</li>
 *   <li>仅当存储状态确为 ACTIVE 时才返回 ACTIVE。</li>
 * </ul>
 *
 * <p>异常仅记录类型与文档标识，不回显连接串、凭据或堆栈，避免敏感信息泄露。</p>
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
            log.warn("生命周期解析跳过：documentId 为空，按 UNKNOWN 处理");
            return DocumentLifecycleStatus.UNKNOWN;
        }
        try {
            KnowledgeDocumentPO po = mapper.selectOne(
                    new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
            if (po == null) {
                log.info("生命周期解析未找到文档，按 NOT_FOUND 处理: documentId={}", documentId);
                return DocumentLifecycleStatus.NOT_FOUND;
            }
            DocumentLifecycleStatus status =
                    DocumentLifecycleStatus.fromName(po.getLifecycleStatus());
            if (status == DocumentLifecycleStatus.UNKNOWN) {
                log.warn("生命周期存储状态无法识别，按 UNKNOWN 处理: documentId={}", documentId);
            }
            return status;
        } catch (RuntimeException ex) {
            log.error("生命周期查询失败，按 LOOKUP_FAILED 处理，异常类型: {}",
                    ex.getClass().getSimpleName());
            return DocumentLifecycleStatus.LOOKUP_FAILED;
        }
    }
}
