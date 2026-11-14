package com.zl.d31.document.vectorindex.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d31.document.mapper.DocumentChunkIndexMapper;
import com.zl.d31.document.mapper.DocumentVectorIndexMapper;
import com.zl.d31.document.po.DocumentVectorIndexPO;
import com.zl.d31.document.vectorindex.model.VectorIndexStatus;
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
 */
@Component
public class VectorIndexStateTx {

    private final DocumentVectorIndexMapper indexMapper;

    private final DocumentChunkIndexMapper chunkMapper;

    public VectorIndexStateTx(DocumentVectorIndexMapper indexMapper,
                              DocumentChunkIndexMapper chunkMapper) {
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
    }

    /**
     * 版本切换（MySQL 本地事务）：
     * 同文档旧的 COMPLETED 版本置 SUPERSEDED，当前版本置 COMPLETED。
     *
     * <p>新版本向量在此之前已全部写入并核对，因此这里只切换活动指针，
     * 不做“先删旧、再写新”，新版本失败不会导致旧版本全丢。</p>
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
    }

    /**
     * 删除文档的 MySQL 关联行与状态行（本地事务），在 PG 向量删除成功后调用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocumentRows(String documentId) {
        chunkMapper.deleteByDocumentId(documentId);
        indexMapper.delete(new QueryWrapper<DocumentVectorIndexPO>()
                .eq("document_id", documentId));
    }

    /**
     * 删除指定旧版本的关联行（旧版本向量清理后调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOldVersionRows(String documentId, Integer version) {
        chunkMapper.deleteByDocumentVersion(documentId, version);
    }
}
