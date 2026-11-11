package com.zl.d28.document.model;

import com.zl.d28.document.po.DocumentPageContentPO;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档页面级提取内容领域模型。
 *
 * <p>承载原始文档某一物理页的提取文本与来源定位，与原始文件、
 * {@link KnowledgeDocument} 元数据分离，也不是后续 RAG 的 Chunk。</p>
 *
 * <p>PO 为 {@link DocumentPageContentPO}，对外使用页面视图。</p>
 */
@Data
public class DocumentPageContent {

    /** 代理主键。 */
    private Long id;

    /** 关联知识文档业务标识。 */
    private String documentId;

    /** 文档来源（保留元数据）。 */
    private String source;

    /** 文档标题（保留元数据）。 */
    private String title;

    /** 文档版本。 */
    private Integer version;

    /** 物理页序号，从 1 开始。 */
    private Integer pageNumber;

    /** 该页提取文本。 */
    private String content;

    /** 该页文本字符长度。 */
    private Integer contentLength;

    /** 是否空白页。 */
    private Boolean blank;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;
}
