package com.zl.d31.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档 Chunk 关联表 {@code document_chunk_index} 的持久化对象。
 *
 * <p>一个 Chunk 一行，承载稳定 {@code chunkId}、顺序、内容指纹与偏移，
 * 是按文档查询、孤儿核对与补偿的权威业务关联；向量本体在 PG 表中。</p>
 */
@Data
@NoArgsConstructor
@TableName("document_chunk_index")
public class DocumentChunkIndexPO {

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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
