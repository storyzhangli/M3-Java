package com.zl.d31.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d31.document.po.DocumentChunkIndexPO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档 Chunk 关联表 Mapper。
 *
 * <p>单表 CRUD 由 {@link BaseMapper} 提供；另提供数据库级幂等 Upsert，
 * 配合唯一约束 {@code uk_chunk_id} 与 {@code uk_doc_ver_strat_idx}
 * 防止并发重复关联，不依赖 Java 层先查后插。</p>
 */
@Mapper
public interface DocumentChunkIndexMapper extends BaseMapper<DocumentChunkIndexPO> {

    /**
     * 幂等写入 Chunk 关联行：冲突键 chunk_id 或 document_id+version+strategy+chunk_index。
     */
    @Insert("""
            INSERT INTO document_chunk_index
                (document_id, version, strategy, chunk_id, chunk_index, content,
                 chunk_content_hash, char_length, global_start, global_end,
                 status, created_at, updated_at)
            VALUES
                (#{documentId}, #{version}, #{strategy}, #{chunkId}, #{chunkIndex}, #{content},
                 #{chunkContentHash}, #{charLength}, #{globalStart}, #{globalEnd},
                 #{status}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                chunk_content_hash = VALUES(chunk_content_hash),
                char_length = VALUES(char_length),
                global_start = VALUES(global_start),
                global_end = VALUES(global_end),
                status = VALUES(status),
                updated_at = VALUES(updated_at)
            """)
    int upsertOnNaturalKey(DocumentChunkIndexPO po);

    @Select("""
            SELECT * FROM document_chunk_index
            WHERE document_id = #{documentId}
            ORDER BY version, strategy, chunk_index
            """)
    List<DocumentChunkIndexPO> selectByDocumentId(@Param("documentId") String documentId);

    @Select("""
            SELECT * FROM document_chunk_index
            WHERE document_id = #{documentId} AND version = #{version}
            ORDER BY strategy, chunk_index
            """)
    List<DocumentChunkIndexPO> selectByDocumentVersion(@Param("documentId") String documentId,
                                                       @Param("version") Integer version);

    @Delete("""
            DELETE FROM document_chunk_index WHERE document_id = #{documentId}
            """)
    int deleteByDocumentId(@Param("documentId") String documentId);

    @Delete("""
            DELETE FROM document_chunk_index
            WHERE document_id = #{documentId} AND version = #{version}
            """)
    int deleteByDocumentVersion(@Param("documentId") String documentId,
                                @Param("version") Integer version);
}
