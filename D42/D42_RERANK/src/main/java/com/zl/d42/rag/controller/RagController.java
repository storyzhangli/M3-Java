package com.zl.d42.rag.controller;

import com.zl.d42.common.Result;
import com.zl.d42.rag.dto.RagAnswerResponse;
import com.zl.d42.rag.dto.RagQuestionRequest;
import com.zl.d42.rag.orchestrator.RagOrchestrator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 最小 RAG HTTP 接口。
 *
 * <p>基址 {@code /api/v1/rag}，仅暴露一个问答入口；统一响应、参数校验与
 * 异常处理沿用现有规范，不暴露密钥/连接信息/堆栈。</p>
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final RagOrchestrator ragOrchestrator;

    public RagController(RagOrchestrator ragOrchestrator) {
        this.ragOrchestrator = ragOrchestrator;
    }

    /**
     * RAG 问答。
     *
     * @param request 问答请求（含可选 Top-K 与已验证过滤字段）
     * @return 统一包装的 RAG 响应
     */
    @PostMapping("/ask")
    public Result<RagAnswerResponse> ask(@Valid @RequestBody RagQuestionRequest request) {
        return Result.success(ragOrchestrator.ask(request));
    }
}
