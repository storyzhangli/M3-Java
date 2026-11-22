package com.zl.d39.document.retrieval;

import com.zl.d39.common.Result;
import com.zl.d39.document.retrieval.SimilaritySearchService;
import com.zl.d39.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d39.document.retrieval.dto.SimilaritySearchResultView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 相似度检索最小可用接口（不含完整 RAG / Prompt / LLM / Tool / MCP / Agent）。
 *
 * <p>仅负责把用户问题转为 Top-K 相关 Chunk 结果；统一返回 {@link Result}，无鉴权，
 * 供本地开发/学习与人工相关性评估使用。</p>
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class SimilaritySearchController {

    private final SimilaritySearchService similaritySearchService;

    public SimilaritySearchController(SimilaritySearchService similaritySearchService) {
        this.similaritySearchService = similaritySearchService;
    }

    /**
     * Top-K 相似度检索：默认 Top-5，支持元数据/版本过滤。
     *
     * @param request 检索请求
     * @return 检索结果（含真实 similarity/distance 与来源标识）
     */
    @PostMapping("/search")
    public Result<SimilaritySearchResultView> search(@Valid @RequestBody SimilaritySearchRequest request) {
        return Result.success(similaritySearchService.search(request));
    }
}
