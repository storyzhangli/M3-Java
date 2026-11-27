package com.zl.d44.one.web;

import com.zl.d44.common.Result;
import com.zl.d44.one.rag.OneRagOrchestrator;
import com.zl.d44.rag.dto.RagAnswerResponse;
import com.zl.d44.rag.dto.RagQuestionRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * one_ 轨 RAG 问答入口（POST /api/v1/one/rag/ask）。
 *
 * <p>请求/响应契约只读复用共享 {@link RagQuestionRequest}/{@link RagAnswerResponse}；
 * 决策链与 local 轨一致，检索源为 one_ 1024 向量空间。</p>
 */
@RestController
@RequestMapping("/api/v1/one/rag")
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneRagController {

    private final OneRagOrchestrator oneRagOrchestrator;

    public OneRagController(OneRagOrchestrator oneRagOrchestrator) {
        this.oneRagOrchestrator = oneRagOrchestrator;
    }

    @PostMapping("/ask")
    public Result<RagAnswerResponse> ask(@Valid @RequestBody RagQuestionRequest request) {
        return Result.success(oneRagOrchestrator.ask(request));
    }
}