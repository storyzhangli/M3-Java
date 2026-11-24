package com.zl.d41.one.index.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * one_ 轨文档 Chunk 关联表 {@code one_document_chunk_index} 持久化对象。
 *
 * <p>结构对齐 local 轨 {@code DocumentChunkIndexPO}，新增 {@code vectorSpaceId}。
 * chunkId 复用 StableChunkIdGenerator（与 local 轨同逻辑 chunk 同 UUID），
 * uk_chunk_id 按表独立唯一，支持跨轨按 chunk 对齐对比；向量本体在 one_d41_vector_store。</p>
 */
@Data
@NoArgsConstructor
@TableName("one_document_chunk_index")
public class OneDocumentChunkIndexPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    private Integer version;

    private String strategy;

    private String chunkId;

    private Integer chunkIndex;

    private String content;

    private String chunkContentHash;

    private Integer charLength;

    private Integer globalStart;

    private Integer globalEnd;

    private String status;

    /** one_ 轨向量空间标识。 */
    private String vectorSpaceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}