package com.zl.d31.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d31.document.po.DocumentVectorIndexPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 文档向量索引状态表 Mapper。
 *
 * <p>单表 CRUD 由 {@link BaseMapper} 提供；另提供数据库级幂等 Upsert，
 * 配合唯一约束 {@code uk_doc_version} 防止并发重复建任务，
 * 不依赖 Java 层先查后插。</p>
 */
@Mapper
public interface DocumentVectorIndexMapper extends BaseMapper<DocumentVectorIndexPO> {

    /**
     * 幂等创建/更新索引任务行：冲突键 document_id + version。
     * 仅在新建时写入默认状态，已存在行的状态由业务流程显式更新，
     * 因此这里不覆盖 status/stage，只保证任务行必然存在。
     */
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("""
            INSERT INTO document_vector_index
                (document_id, version, content_hash, strategy, chunk_count,
                 status, stage, error_code, error_message, attempt_count,
                 next_retry_at, created_at, updated_at)
            VALUES
                (#{documentId}, #{version}, #{contentHash}, #{strategy}, #{chunkCount},
                 #{status}, #{stage}, #{errorCode}, #{errorMessage}, #{attemptCount},
                 #{nextRetryAt}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                content_hash = VALUES(content_hash),
                updated_at = VALUES(updated_at)
            """)
    int upsertOnNaturalKey(DocumentVectorIndexPO po);

    /**
     * 状态机推进：显式更新状态/阶段/错误/重试计数。
     */
    @Update("""
            UPDATE document_vector_index
            SET status = #{status},
                stage = #{stage},
                error_code = #{errorCode},
                error_message = #{errorMessage},
                attempt_count = #{attemptCount},
                next_retry_at = #{nextRetryAt},
                chunk_count = #{chunkCount},
                updated_at = #{updatedAt}
            WHERE document_id = #{documentId} AND version = #{version}
            """)
    int updateState(@Param("documentId") String documentId,
                    @Param("version") Integer version,
                    @Param("status") String status,
                    @Param("stage") String stage,
                    @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage,
                    @Param("attemptCount") Integer attemptCount,
                    @Param("nextRetryAt") LocalDateTime nextRetryAt,
                    @Param("chunkCount") Integer chunkCount,
                    @Param("updatedAt") LocalDateTime updatedAt);
}
