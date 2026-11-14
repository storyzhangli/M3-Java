package com.zl.d31.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档页面级提取结果表 {@code knowledge_document_page} 的持久化对象。
 *
 * <p>PO 只负责与数据库行映射，不直接暴露给 Controller。</p>
 */
@Data
@NoArgsConstructor
@TableName("knowledge_document_page")
public class DocumentPageContentPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    private String source;

    private String title;

    private Integer version;

    private Integer pageNumber;

    private String content;

    private Integer contentLength;

    private Integer blank;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
