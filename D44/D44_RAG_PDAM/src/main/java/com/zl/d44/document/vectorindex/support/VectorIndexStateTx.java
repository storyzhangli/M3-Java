package com.zl.d44.document.vectorindex.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d44.document.mapper.DocumentChunkIndexMapper;
import com.zl.d44.document.mapper.DocumentVectorIndexMapper;
import com.zl.d44.document.mapper.KnowledgeDocumentMapper;
import com.zl.d44.document.po.DocumentVectorIndexPO;
import com.zl.d44.document.sync.mapper.DocumentSyncMapper;
import com.zl.d44.document.sync.po.DocumentSyncPO;
import com.zl.d44.document.vectorindex.model.VectorIndexStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 向量索引状态的本地事务边界（MySQL 单库）。
 *
 * <p>把需要原子性的多步 MySQL 写收敛到独立 Bean，确保 Spring 代理生效；
 * 不包含 PG 调用与 Embedding 调用，避免把外部系统纳入本地事务。
 * 不引入分布式事务框架，跨系统一致性由状态机 + 核对补偿保证。</p>
 *
 * <p><b>P2 追加</b>（任务书 §2 P2 白名单）：</p>
 * <ul>
 *   <li>{@link #switchVersion} 同一事务内更新 knowledge_document.version=targetVersion
 *       （D3 裁决：版本指针权威化，消除文档元数据与活动索引版本天然漂移）；</li>
 *   <li>{@link #markDeletedTx}：lifecycle→DEPRECATED + sync PENDING 原子落库
 *       （D2 裁决：删除先标记，同一 MySQL 本地事务，无分布式事务）。</li>
 * </ul>
 */
@Component
public class VectorIndexStateTx {

    private final DocumentVectorIndexMapper indexMapper;

    private final DocumentChunkIndexMapper chunkMapper;

    /** P2：版本指针与 tombstone（表/Bean 恒存在，直接构造注入）。 */
    private final KnowledgeDocumentMapper documentMapper;

    /** P2：删除标记与 sync PENDING 同事务落库。 */
    private final DocumentSyncMapper syncMapper;

    /** P2 追加：表格结构化分支清理（删除/旧版本清表随同一本地事务，D7 复用不建新状态机）。 */
    private final com.zl.d44.document.table.service.TableRecordService tableRecordService;

    public VectorIndexStateTx(DocumentVectorIndexMapper indexMapper,
                              DocumentChunkIndexMapper chunkMapper,
                              KnowledgeDocumentMapper documentMapper,
                              DocumentSyncMapper syncMapper,
                              com.zl.d44.document.table.service.TableRecordService tableRecordService) {
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.syncMapper = syncMapper;
        this.tableRecordService = tableRecordService;
    }

    /**
     * 版本切换（MySQL 本地事务）：
     * 同文档旧的 COMPLETED 版本置 SUPERSEDED，当前版本置 COMPLETED；
     * 追加：knowledge_document.version 原子推进到当前版本（指针权威化）。
     *
     * <p>新版本向量在此之前已全部写入并核对，因此这里只切换活动指针，
     * 不做"先删旧、再写新"，新版本失败不会导致旧版本全丢。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void switchVersion(DocumentVectorIndexPO current,
                              List<DocumentVectorIndexPO> olderCompleted,
                              LocalDateTime now) {
        for (DocumentVectorIndexPO older : olderCompleted) {
            older.setStatus(VectorIndexStatus.SUPERSEDED.name());
            older.setUpdatedAt(now);
            indexMapper.updateById(older);
        }
        current.setStatus(VectorIndexStatus.COMPLETED.name());
        current.setUpdatedAt(now);
        indexMapper.updateById(current);
        // P2（D3 裁决）：同一事务推进文档版本指针；行不存在/已更高版本 → 0 行无害
        documentMapper.bumpVersionPointer(current.getDocumentId(), current.getVersion(), now);
    }

    /**
     * 删除文档的 MySQL 关联行与状态行（本地事务），在 PG 向量删除成功后调用。
     * 追加：表格结构化记录随同一事务清理（全版本），不残留孤儿表行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocumentRows(String documentId) {
        chunkMapper.deleteByDocumentId(documentId);
        indexMapper.delete(new QueryWrapper<DocumentVectorIndexPO>()
                .eq("document_id", documentId));
        tableRecordService.deleteAllForDocument(documentId);
    }

    /**
     * 删除指定旧版本的关联行（旧版本向量清理后调用）。
     * 追加：同版本表格记录+行一并清理。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOldVersionRows(String documentId, Integer version) {
        chunkMapper.deleteByDocumentVersion(documentId, version);
        tableRecordService.deleteForVersion(documentId, version);
    }

    /**
     * P2（D2 裁决）：删除标记事务——knowledge_document.lifecycle_status→DEPRECATED
     * 与 d44_document_sync PENDING <b>同一 MySQL 本地事务</b>原子落库。
     *
     * <p>tombstone 保留文档行（不硬删）：审计语义 + 引用核验 doc-exists 语义不变
     * + 避免分类路径回退（D8）。sync 行经 uk_doc_op_version upsert：重复删除事件
     * 覆盖同一行而非新增（E4 防重复事件）。</p>
     *
     * @return sync 行对象（调用方失败补偿引用）
     */
    @Transactional(rollbackFor = Exception.class)
    public DocumentSyncPO markDeletedTx(String documentId, DocumentSyncPO pendingRow,
                                        LocalDateTime now) {
        documentMapper.markDeprecated(documentId, now);
        syncMapper.upsertOnNaturalKey(pendingRow);
        return pendingRow;
    }

    /**
     * P4（H4）：版本指针重放——DRIFT_VERSION_MISMATCH(a) 修复入口。
     * SWITCH 同型 MySQL 本地事务，但<b>不复用</b> bumpVersionPointer 的单调前进守卫：
     * 修复语义=把漂移指针（C12b 实测可大于活动版本）覆写回权威 COMPLETED 版本，
     * 前进守卫会静默 0 行失效（C12b 第一遍实测缺陷）。覆写天然幂等，重复执行零副作用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void repointVersionPointer(String documentId, Integer targetVersion,
                                      LocalDateTime now) {
        documentMapper.setVersionPointer(documentId, targetVersion, now);
    }
}
