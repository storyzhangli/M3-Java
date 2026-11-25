package com.zl.d42.rag.rerank;

/**
 * 精排候选：类型化输入单元（D10 禁无约束 Map）。
 *
 * @param ordinal 原始向量序中的位次（0-based，重排映射的权威键）
 * @param chunkId 稳定 Chunk ID（透传自 SimilarityMatchView，仅用于日志/证据关联）
 * @param content Chunk 文本内容（精排打分输入；永不回显到公开日志明文之外）
 */
public record RerankCandidate(int ordinal, String chunkId, String content) {

    /** 防御性空内容口径：null 视为空串，不参与内容匹配但保留候选身份。 */
    public String contentOrEmpty() {
        return content == null ? "" : content;
    }
}
