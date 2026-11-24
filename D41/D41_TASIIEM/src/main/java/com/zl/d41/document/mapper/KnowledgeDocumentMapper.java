package com.zl.d41.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d41.document.po.KnowledgeDocumentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 知识文档表 Mapper。单表 CRUD 由 {@link BaseMapper} 提供，不额外编写 XML。
 *
 * <p><b>P2 追加</b>（既有列零 DDL）：版本指针权威化（D3 裁决）与
 * tombstone 生命周期标记（D2 裁决）——均由 {@code VectorIndexStateTx}
 * 在 MySQL 本地事务内调用，不单独提交。</p>
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentPO> {

    /**
     * 版本指针更新（SWITCH 同事务追加）：仅向前推进（{@code version < target}），
     * 幂等重跑二次执行 0 行无害；文档行不存在 0 行（直接 /index 入库的未登记文档属预期）。
     */
    @Update("""
            UPDATE knowledge_document
               SET version = #{targetVersion}, updated_at = #{now}
             WHERE document_id = #{documentId} AND version < #{targetVersion}
            """)
    int bumpVersionPointer(@Param("documentId") String documentId,
                           @Param("targetVersion") Integer targetVersion,
                           @Param("now") LocalDateTime now);

    /**
     * P4 修复（C12b 实测缺陷）：版本指针<b>权威重放</b>——无单调前进守卫。
     *
     * <p>{@link #bumpVersionPointer} 的 {@code version < target} 守卫服务 SWITCH"只前进"
     * 语义；而一致性修复（DRIFT_VERSION_MISMATCH (a) 路）是把漂移指针（可能大于活动版本）
     * 重放回权威 COMPLETED 版本，语义=覆写非推进。复用守卫 SQL 会静默 0 行失效。
     * 仅由 {@code VectorIndexStateTx.repointVersionPointer} 在 MySQL 本地事务内调用。</p>
     */
    @Update("""
            UPDATE knowledge_document
               SET version = #{targetVersion}, updated_at = #{now}
             WHERE document_id = #{documentId}
            """)
    int setVersionPointer(@Param("documentId") String documentId,
                          @Param("targetVersion") Integer targetVersion,
                          @Param("now") LocalDateTime now);

    /**
     * tombstone 标记（删除闭环同事务）：ACTIVE/DRAFT → DEPRECATED；
     * 已是 DEPRECATED 幂等覆盖无副作用；文档行不存在 0 行（不阻断删除管线）。
     */
    @Update("""
            UPDATE knowledge_document
               SET lifecycle_status = 'DEPRECATED', updated_at = #{now}
             WHERE document_id = #{documentId}
            """)
    int markDeprecated(@Param("documentId") String documentId,
                       @Param("now") LocalDateTime now);
}
