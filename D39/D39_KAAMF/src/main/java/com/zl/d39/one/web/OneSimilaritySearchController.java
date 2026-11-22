package com.zl.d39.one.web;

import com.zl.d39.common.Result;
import com.zl.d39.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d39.document.retrieval.dto.SimilaritySearchResultView;
import com.zl.d39.one.retrieval.OneSimilaritySearchService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * one_ 轨相似度检索入口（POST /api/v1/one/retrieval/search）。
 *
 * <p>请求/响应 DTO 只读复用 local 契约类，响应结构逐字段对齐：
 * data.matches + total + vectorSpaceId + filtered + 三段计时。</p>
 */
@RestController
@RequestMapping("/api/v1/one/retrieval")
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneSimilaritySearchController {

    private final OneSimilaritySearchService searchService;

    public OneSimilaritySearchController(OneSimilaritySearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public Result<SimilaritySearchResultView> search(@Valid @RequestBody SimilaritySearchRequest request) {
        return Result.success(searchService.search(request));
    }
}