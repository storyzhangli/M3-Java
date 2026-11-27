package com.zl.d44.rag.retrieval;

/**
 * RAG 内部检索查询（与 HTTP DTO 分离）。
 *
 * @param question   已归一化问题
 * @param topK       可选 Top-K，null 用默认
 * @param documentId 可选文档过滤
 * @param version    可选版本过滤
 * @param strategy   可选策略过滤
 * @param title      可选标题过滤
 */
public record RagRetrievalQuery(String question,
                                Integer topK,
                                String documentId,
                                Integer version,
                                String strategy,
                                String title) {
}
