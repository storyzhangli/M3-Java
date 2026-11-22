package com.zl.d39.one.index;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d39.document.vectorindex.model.VectorIndexStatus;
import com.zl.d39.one.index.mapper.OneDocumentChunkIndexMapper;
import com.zl.d39.one.index.mapper.OneDocumentVectorIndexMapper;
import com.zl.d39.one.index.po.OneDocumentVectorIndexPO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * one_ 轨向量索引状态本地事务边界（MySQL 单库，语义对齐 local 轨 VectorIndexStateTx）。
 *
 * <p>只收敛 MySQL 多步写的原子性，不含 PG/Embedding 调用；
 * 跨系统一致性由状态机 + 核对补偿收敛，不引入分布式事务。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneVectorIndexStateTx {

    private final OneDocumentVectorIndexMapper indexMapper;
    private final OneDocumentChunkIndexMapper chunkMapper;

    public OneVectorIndexStateTx(OneDocumentVectorIndexMapper indexMapper,
                                 OneDocumentChunkIndexMapper chunkMapper) {
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
    }

    /** 版本切换：旧 COMPLETED 置 SUPERSEDED，当前置 COMPLETED（先写后切，失败不丢旧版本）。 */
    @Transactional(rollbackFor = Exception.class)
    public void switchVersion(OneDocumentVectorIndexPO current,
                              List<OneDocumentVectorIndexPO> olderCompleted,
                              LocalDateTime now) {
        for (OneDocumentVectorIndexPO older : olderCompleted) {
            older.setStatus(VectorIndexStatus.SUPERSEDED.name());
            older.setUpdatedAt(now);
            indexMapper.updateById(older);
        }
        current.setStatus(VectorIndexStatus.COMPLETED.name());
        current.setUpdatedAt(now);
        indexMapper.updateById(current);
    }

    /** 删除文档 MySQL 关联行与状态行（PG 向量删除成功后调用）。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocumentRows(String documentId) {
        chunkMapper.deleteByDocumentId(documentId);
        indexMapper.delete(new QueryWrapper<OneDocumentVectorIndexPO>()
                .eq("document_id", documentId));
    }

    /** 删除指定旧版本关联行（旧版本向量清理后调用）。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOldVersionRows(String documentId, Integer version) {
        chunkMapper.deleteByDocumentVersion(documentId, version);
    }
}