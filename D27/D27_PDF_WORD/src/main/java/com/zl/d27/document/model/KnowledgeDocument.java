package com.zl.d27.document.model;

import com.zl.d27.document.po.KnowledgeDocumentPO;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档领域模型。
 *
 * <p>字段体系对齐 D25 KnowledgeDocument 字典的最小子集；与持久化对象
 * {@link KnowledgeDocumentPO} 之间由服务层转换，
 * 领域模型不直接暴露给 Controller（对外使用 View）。</p>
 */
@Data
public class KnowledgeDocument {

    /** 代理主键。 */
    private Long id;

    /** 文档业务标识。 */
    private String documentId;

    /** 文档来源（对应 D25 sourceSystem）。 */
    private String source;

    /** 文档标题。 */
    private String title;

    /** 文档版本，从 1 开始。 */
    private Integer version;

    /** 导入处理状态。 */
    private DocumentStatus status;

    /** 原始字节 SHA-256。 */
    private String contentHash;

    /** 原始字节长度。 */
    private Long contentLength;

    /** 失败错误码。 */
    private String errorCode;

    /** 安全错误摘要。 */
    private String errorMessage;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 状态更新时间。 */
    private LocalDateTime updatedAt;
}
