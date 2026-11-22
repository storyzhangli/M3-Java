package com.zl.d39.rag.retrieval;

import com.zl.d39.document.retrieval.dto.SimilarityMatchView;
import com.zl.d39.rag.model.RetrievalStatus;

import java.util.List;

/**
 * RAG 检索结果：状态 + 命中 + 安全失败说明。
 *
 * @param status        检索状态（SUCCESS/EMPTY/FAILED）
 * @param matches       命中视图列表（失败时为空列表）
 * @param failureReason 安全失败说明（仅通用提示，不含上游细节）
 */
public record RagRetrievalResult(RetrievalStatus status,
                                 List<SimilarityMatchView> matches,
                                 String failureReason) {

    public int retrievedCount() {
        return matches == null ? 0 : matches.size();
    }
}
