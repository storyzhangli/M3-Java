package com.zl.d29.document.chunk.model;

/**
 * Chunk 构建上下文：承载文档身份元数据，切分器据此回填 documentId/title/version。
 *
 * @param documentId 文档业务标识
 * @param title      文档标题
 * @param version    文档版本
 */
public record ChunkContext(String documentId, String title, Integer version) {
}
