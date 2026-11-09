package com.zl.d27.document.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档对外视图。PO 与领域模型不直接暴露，统一由该视图承载。
 */
@Data
public class DocumentView {

    private String documentId;

    private String source;

    private String title;

    private Integer version;

    private String status;

    private Long contentLength;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
