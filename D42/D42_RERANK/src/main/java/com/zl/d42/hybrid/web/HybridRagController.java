package com.zl.d42.hybrid.web;

import com.zl.d42.common.Result;
import com.zl.d42.hybrid.rag.HybridRagAnswerResponse;
import com.zl.d42.hybrid.rag.HybridRagOrchestrator;
import com.zl.d42.rag.dto.RagQuestionRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 混合检索 RAG 问答端点：{@code POST /api/v1/hybrid/rag/ask}。
 *
 * <p>请求 DTO 复用共享 {@link RagQuestionRequest}（只读），响应为
 * {@link HybridRagAnswerResponse}（继承 RagAnswerResponse + fusionTrace），
 * 决策/生成/核验链路零修改，唯一变量=检索策略（H3）。</p>
 */
@RestController
@RequestMapping("/api/v1/hybrid/rag")
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class HybridRagController {

    private final HybridRagOrchestrator hybridRagOrchestrator;

    public HybridRagController(
            @Qualifier("hybridRagOrchestrator") HybridRagOrchestrator hybridRagOrchestrator) {
        this.hybridRagOrchestrator = hybridRagOrchestrator;
    }

    @PostMapping("/ask")
    public Result<HybridRagAnswerResponse> ask(@Valid @RequestBody RagQuestionRequest request) {
        return Result.success(hybridRagOrchestrator.ask(request));
    }
}
