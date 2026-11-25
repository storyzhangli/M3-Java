package com.zl.d42.rag.retrieval;

import com.zl.d42.access.AccessPolicyService;
import com.zl.d42.document.retrieval.SimilaritySearchService;
import com.zl.d42.document.retrieval.dto.SimilarityMatchView;
import com.zl.d42.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d42.rag.model.RetrievalStatus;
import com.zl.d42.rag.retrieval.RagRetrievalQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 检索服务：负责 {@code Query Embedding → VectorStore → Top-K}，
 * 内部复用 {@link SimilaritySearchService}，不重复实现检索基础设施。
 *
 * <p>明确区分“成功但无结果”({@link RetrievalStatus#EMPTY}) 与
 * “执行失败”({@link RetrievalStatus#FAILED})。本服务不调用 ChatModel。</p>
 */
@Slf4j
@Service
public class RagRetrievalService {

    private final SimilaritySearchService similaritySearchService;

    /**
     * P3 追加：权限服务可选依赖。enabled 时在<b>try 之外</b>预门禁 requireCurrent——
     * 无服务端上下文抛 AccessDeniedException（4030，H5 fail-closed），
     * 绝不落入本方法 catch(RuntimeException)→FAILED 的吞异常路径；
     * disabled 时 Bean 缺席 → 门禁不启用，链路与引入前等价（H1）。
     * 检索谓词注入在 SimilaritySearchService 内部完成（单一代数源，本层不重复拼谓词）。
     */
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    public RagRetrievalService(SimilaritySearchService similaritySearchService,
                               ObjectProvider<AccessPolicyService> accessPolicyProvider) {
        this.similaritySearchService = similaritySearchService;
        this.accessPolicyProvider = accessPolicyProvider;
    }

    /**
     * 执行检索。
     *
     * @param query 内部检索查询
     * @return 检索结果（永不返回 null）
     */
    public RagRetrievalResult retrieve(RagRetrievalQuery query) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy != null) {
            policy.requireCurrent();
        }
        SimilaritySearchRequest request = buildRequest(query);
        try {
            var view = similaritySearchService.search(request);
            List<SimilarityMatchView> matches = view.getMatches();
            if (matches == null || matches.isEmpty()) {
                log.info("RAG 检索成功但无命中: questionLength={}", query.question().length());
                return new RagRetrievalResult(RetrievalStatus.EMPTY, List.of(), null);
            }
            log.info("RAG 检索成功: total={}", matches.size());
            return new RagRetrievalResult(RetrievalStatus.SUCCESS, matches, null);
        } catch (RuntimeException ex) {
            // 不回显上游细节，仅记录异常类型；对外给通用安全提示。
            log.error("RAG 检索执行失败，异常类型: {}", ex.getClass().getSimpleName());
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
