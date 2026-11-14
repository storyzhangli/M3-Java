package com.zl.d31.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档向量索引状态表 {@code document_vector_index} 的持久化对象。
 *
 * <p>一个文档版本一行，承载状态机、阶段、重试计数与错误信息，
 * 兼作跨系统一致性的轻量 Outbox/任务记录；不复制向量本体。</p>
 */
@Data
@NoArgsConstructor
@TableName("document_vector_index")
public class DocumentVectorIndexPO {

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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
