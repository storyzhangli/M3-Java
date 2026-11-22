package com.zl.d39.one.rag;

import com.zl.d39.document.retrieval.dto.SimilarityMatchView;
import com.zl.d39.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d39.one.retrieval.OneSimilaritySearchService;
import com.zl.d39.rag.model.RetrievalStatus;
import com.zl.d39.rag.retrieval.RagRetrievalQuery;
import com.zl.d39.rag.retrieval.RagRetrievalResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * one_ 轨 RAG 检索服务：内部复用 {@link OneSimilaritySearchService}（one_ 空间），
 * 结果类型只读复用共享 {@link RagRetrievalResult}，供 OneRagOrchestrator 消费。
 *
 * <p>明确区分 EMPTY（成功无命中）与 FAILED（执行失败）；不调用 ChatModel。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneRagRetrievalService {

    private final OneSimilaritySearchService oneSimilaritySearchService;

    public OneRagRetrievalService(OneSimilaritySearchService oneSimilaritySearchService) {
        this.oneSimilaritySearchService = oneSimilaritySearchService;
    }

    public RagRetrievalResult retrieve(RagRetrievalQuery query) {
        SimilaritySearchRequest request = buildRequest(query);
        try {
            var view = oneSimilaritySearchService.search(request);
            List<SimilarityMatchView> matches = view.getMatches();
            if (matches == null || matches.isEmpty()) {
                log.info("one_ RAG 检索成功但无命中: questionLength={}", query.question().length());
                return new RagRetrievalResult(RetrievalStatus.EMPTY, List.of(), null);
            }
            log.info("one_ RAG 检索成功: total={}", matches.size());
            return new RagRetrievalResult(RetrievalStatus.SUCCESS, matches, null);
        } catch (RuntimeException ex) {
            log.error("one_ RAG 检索执行失败，异常类型: {}", ex.getClass().getSimpleName());
            return new RagRetrievalResult(
                    RetrievalStatus.FAILED, List.of(), "检索服务执行失败，请稍后重试");
        }
    }

    private SimilaritySearchRequest buildRequest(RagRetrievalQuery query) {
        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery(query.question());
        request.setTopK(query.topK());
        request.setDocumentId(blankToNull(query.documentId()));
        request.setVersion(query.version());
        request.setStrategy(blankToNull(query.strategy()));
        request.setTitle(blankToNull(query.title()));
        return request;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}