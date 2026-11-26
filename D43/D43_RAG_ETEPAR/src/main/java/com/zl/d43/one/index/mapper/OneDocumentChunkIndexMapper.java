package com.zl.d43.one.index.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zl.d43.one.index.po.OneDocumentChunkIndexPO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * one_ 轨文档 Chunk 关联表 Mapper（表 one_document_chunk_index）。
 *
 * <p>语义对齐 local 轨 DocumentChunkIndexMapper：DB 级幂等 Upsert
 * （uk_chunk_id / uk_doc_ver_strat_idx），upsert <b>不更新 chunk_id 列</b>
 * （D37 教训：同文档换 strategy 重索引必须先 DELETE 再重建）。</p>
 */
@Mapper
public interface OneDocumentChunkIndexMapper extends BaseMapper<OneDocumentChunkIndexPO> {

    @Insert("""
            INSERT INTO one_document_chunk_index
                (document_id, version, strategy, chunk_id, chunk_index, content,
                 chunk_content_hash, char_length, global_start, global_end,
                 status, vector_space_id, created_at, updated_at)
            VALUES
                (#{documentId}, #{version}, #{strategy}, #{chunkId}, #{chunkIndex}, #{content},
                 #{chunkContentHash}, #{charLength}, #{globalStart}, #{globalEnd},
                 #{status}, #{vectorSpaceId}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                chunk_content_hash = VALUES(chunk_content_hash),
                char_length = VALUES(char_length),
                global_start = VALUES(global_start),
                global_end = VALUES(global_end),
                status = VALUES(status),
                vector_space_id = VALUES(vector_space_id),
                updated_at = VALUES(updated_at)
            """)
    int upsertOnNaturalKey(OneDocumentChunkIndexPO po);

    @Select("""
            SELECT * FROM one_document_chunk_index
            WHERE document_id = #{documentId}
            ORDER BY version, strategy, chunk_index
            """)
    List<OneDocumentChunkIndexPO> selectByDocumentId(@Param("documentId") String documentId);

    @Select("""
            SELECT * FROM one_document_chunk_index
            WHERE document_id = #{documentId} AND version = #{version}
            ORDER BY strategy, chunk_index
            """)
    List<OneDocumentChunkIndexPO> selectByDocumentVersion(@Param("documentId") String documentId,
                                                          @Param("version") Integer version);

    @Delete("""
            DELETE FROM one_document_chunk_index WHERE document_id = #{documentId}
            """)
    int deleteByDocumentId(@Param("documentId") String documentId);

    @Delete("""
            DELETE FROM one_document_chunk_index
            WHERE document_id = #{documentId} AND version = #{version}
            """)
    int deleteByDocumentVersion(@Param("documentId") String documentId,
                                @Param("version") Integer version);
}