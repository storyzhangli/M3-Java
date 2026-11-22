package com.zl.d39.one.index.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * one_ 轨文档向量索引状态表 {@code one_document_vector_index} 持久化对象。
 *
 * <p>结构对齐 local 轨 {@code DocumentVectorIndexPO}，新增 {@code vectorSpaceId}
 * 绑定向量空间（bailian|qwen3.7-text-embedding|remote-v1|1024）；两轨表物理独立。</p>
 */
@Data
@NoArgsConstructor
@TableName("one_document_vector_index")
public class OneDocumentVectorIndexPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    private Integer version;

    private String contentHash;

    private String strategy;

    private Integer chunkCount;

    private String status;

    private String stage;

    private String errorCode;

    private String errorMessage;

    private Integer attemptCount;

    private LocalDateTime nextRetryAt;

    /** one_ 轨向量空间标识（跨空间复用拒绝依据）。 */
    private String vectorSpaceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}