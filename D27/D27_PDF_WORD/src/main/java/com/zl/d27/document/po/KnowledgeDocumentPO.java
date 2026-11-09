package com.zl.d27.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.zl.d27.document.model.KnowledgeDocument;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档表 {@code knowledge_document} 的持久化对象。
 *
 * <p>PO 只负责与数据库行映射，不直接暴露给 Controller；与领域模型
 * {@link KnowledgeDocument} 之间由服务层转换。</p>
 */
@Data
@NoArgsConstructor
@TableName("knowledge_document")
public class KnowledgeDocumentPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    private String source;

    private String title;

    private Integer version;

    private String status;

    private String contentHash;

    private Long contentLength;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
