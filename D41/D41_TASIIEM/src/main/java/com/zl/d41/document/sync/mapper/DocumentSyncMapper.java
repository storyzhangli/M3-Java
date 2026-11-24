package com.zl.d41.document.sync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d41.document.sync.po.DocumentSyncPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步状态表 Mapper：单表 CRUD 由 BaseMapper 提供，另补幂等 Upsert 与调度扫描查询。
 *
 * <p>幂等锚点=唯一键 {@code uk_doc_op_version(document_id, operation, version)}：
 * 同一删除/清理事件重复提交只更新原行，不产生重复行（E4 依据）。</p>
 */
@Mapper
public interface DocumentSyncMapper extends BaseMapper<DocumentSyncPO> {

    /**
     * 自然键幂等 Upsert：重复事件覆盖状态字段，created_at 保留首写值。
     */
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO d41_document_sync
                (document_id, operation, version, sync_status, reason_code,
                 attempt_count, next_retry_at, payload_hash, created_at, updated_at)
            VALUES
                (#{documentId}, #{operation}, #{version}, #{syncStatus}, #{reasonCode},
                 #{attemptCount}, #{nextRetryAt}, #{payloadHash}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                sync_status = VALUES(sync_status),
                reason_code = VALUES(reason_code),
                attempt_count = VALUES(attempt_count),
                next_retry_at = VALUES(next_retry_at),
                payload_hash = VALUES(payload_hash),
                updated_at = VALUES(updated_at)
            """)
    int upsertOnNaturalKey(DocumentSyncPO po);

    /** 自然键单行读取。 */
    @Select("""
            SELECT * FROM d41_document_sync
            WHERE document_id = #{documentId} AND operation = #{operation} AND version = #{version}
            """)
    DocumentSyncPO selectByNaturalKey(@Param("documentId") String documentId,
                                      @Param("operation") String operation,
                                      @Param("version") Integer version);

    /** 自动重试扫描：FAILED ∧ 到期 ∧ 未耗尽（attempt < maxAttempts）。 */
    @Select("""
            SELECT * FROM d41_document_sync
            WHERE sync_status = 'FAILED'
              AND attempt_count < #{maxAttempts}
              AND (next_retry_at IS NULL OR next_retry_at <= #{now})
            ORDER BY updated_at
            LIMIT 200
            """)
    List<DocumentSyncPO> selectDueForRetry(@Param("maxAttempts") int maxAttempts,
                                           @Param("now") LocalDateTime now);

    /** 状态查询面：按文档（可空=全部非 SYNCED 行，供 /sync/status 失败可查询）。 */
    @Select("""
            SELECT * FROM d41_document_sync
            WHERE (#{documentId} IS NULL OR document_id = #{documentId})
            ORDER BY updated_at DESC
            LIMIT 500
            """)
    List<DocumentSyncPO> selectForStatusView(@Param("documentId") String documentId);

    /**
     * R02 原子领取：单条条件 UPDATE 完成 FAILED → PENDING 抢占（数据库层互斥，
     * 替代"查询→内存判断→无条件 updateById"的竞态窗口）。
     *
     * <p>返回值语义：affectedRows==1=本实例领取成功；0=已被其他实例领取、
     * 状态已变化或行不存在——调用方必须放弃执行重试管线。</p>
     *
     * <p>attempt_count 递增点保持冻结语义（markFailed 写入，领取不自增，防双重计数）；
     * 手动耗尽行重置经 newAttemptCount=0 参数化承载。字段/状态名均以本表 DDL 为准
     * （表名 d41_document_sync，非整改任务书示意名 document_sync）。</p>
     */
    @Update("""
            UPDATE d41_document_sync
               SET sync_status = 'PENDING', reason_code = #{reasonCode},
                   next_retry_at = NULL, attempt_count = #{newAttemptCount},
                   updated_at = #{now}
             WHERE id = #{id} AND sync_status = 'FAILED'
            """)
    int claimFailedAtomically(@Param("id") Long id,
                              @Param("newAttemptCount") int newAttemptCount,
                              @Param("reasonCode") String reasonCode,
                              @Param("now") LocalDateTime now);
}
