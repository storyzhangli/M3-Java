package com.zl.d42.one.index.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d42.one.index.po.OneDocumentVectorIndexPO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

/**
 * one_ 轨文档向量索引状态表 Mapper（表 one_document_vector_index）。
 *
 * <p>语义对齐 local 轨 DocumentVectorIndexMapper：DB 级幂等 Upsert（uk_doc_version）
 * + 显式状态机推进；新增 vector_space_id 列写入。</p>
 */
@Mapper
public interface OneDocumentVectorIndexMapper extends BaseMapper<OneDocumentVectorIndexPO> {

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("""
            INSERT INTO one_document_vector_index
                (document_id, version, content_hash, strategy, chunk_count,
                 status, stage, error_code, error_message, attempt_count,
                 next_retry_at, vector_space_id, created_at, updated_at)
            VALUES
                (#{documentId}, #{version}, #{contentHash}, #{strategy}, #{chunkCount},
                 #{status}, #{stage}, #{errorCode}, #{errorMessage}, #{attemptCount},
                 #{nextRetryAt}, #{vectorSpaceId}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                content_hash = VALUES(content_hash),
                vector_space_id = VALUES(vector_space_id),
                updated_at = VALUES(updated_at)
            """)
    int upsertOnNaturalKey(OneDocumentVectorIndexPO po);

    @Update("""
            UPDATE one_document_vector_index
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