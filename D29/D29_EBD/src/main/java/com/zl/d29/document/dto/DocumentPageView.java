package com.zl.d29.document.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档页面级提取内容对外视图。
 *
 * <p>用于来源定位查询：可定位到 documentId 与物理页码 pageNumber。</p>
 */
@Data
public class DocumentPageView {

    private String documentId;

    private String source;

    private String title;

    private Integer version;

    private Integer pageNumber;

    private String content;

    private Integer contentLength;

    private Boolean blank;

    private LocalDateTime updatedAt;
}
