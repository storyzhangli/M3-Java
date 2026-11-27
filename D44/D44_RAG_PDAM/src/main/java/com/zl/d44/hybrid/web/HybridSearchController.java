package com.zl.d44.hybrid.web;

import com.zl.d44.common.Result;
import com.zl.d44.hybrid.search.HybridSearchService;
import com.zl.d44.hybrid.search.dto.HybridSearchRequest;
import com.zl.d44.hybrid.search.dto.HybridSearchResponseView;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 混合检索端点：{@code POST /api/v1/hybrid/retrieval/search}。
 *
 * <p>响应对齐 local 契约并新增 keywordMatches/fusionTrace/scoreSemantic 与分段计时；
 * {@code matches.score} 恒为 RRF 分（scoreSemantic="RRF"），原始余弦保留在
 * {@code similarity}/fusionTrace 供追溯（§3.2 红线）。</p>
 */
@RestController
@RequestMapping("/api/v1/hybrid/retrieval")
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class HybridSearchController {

    private final HybridSearchService hybridSearchService;

    public HybridSearchController(@Qualifier("hybridSearchService") HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @PostMapping("/search")
    public Result<HybridSearchResponseView> search(@Valid @RequestBody HybridSearchRequest request) {
        return Result.success(hybridSearchService.search(request).view());
    }
}
