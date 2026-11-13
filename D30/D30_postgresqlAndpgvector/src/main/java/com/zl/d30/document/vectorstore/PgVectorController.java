package com.zl.d30.document.vectorstore;

import com.zl.d30.common.Result;
import com.zl.d30.document.vectorstore.dto.VectorMatchView;
import com.zl.d30.document.vectorstore.dto.VectorSearchRequest;
import com.zl.d30.document.vectorstore.dto.VectorStatsView;
import com.zl.d30.document.vectorstore.dto.VectorWriteRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * pgvector 向量存储入口：基础写入、相似度检索与统计。
 *
 * <p>HTTP 统一返回 {@link Result}，业务结果通过 code 区分；不实现 RAG 问答，
 * 仅提供向量存储基础能力的可调用接口。</p>
 */
@RestController
@RequestMapping("/api/v1/vector-store")
public class PgVectorController {

    private final PgVectorStoreService service;

    public PgVectorController(PgVectorStoreService service) {
        this.service = service;
    }

    /**
     * 真实向量写入。
     */
    @PostMapping("/documents")
    public Result<Integer> write(@Valid @RequestBody VectorWriteRequest request) {
        return Result.success(service.addItems(request.getItems()));
    }

    /**
     * 真实余弦相似度检索。
     */
    @PostMapping("/search")
    public Result<List<VectorMatchView>> search(@Valid @RequestBody VectorSearchRequest request) {
        return Result.success(service.search(request));
    }

    /**
     * 向量表统计。
     */
    @GetMapping("/stats")
    public Result<VectorStatsView> stats() {
        return Result.success(new VectorStatsView(service.qualifiedTable(), service.count()));
    }
}
