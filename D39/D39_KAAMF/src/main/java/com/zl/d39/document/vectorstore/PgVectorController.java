package com.zl.d39.document.vectorstore;

import com.zl.d39.access.AccessPolicyService;
import com.zl.d39.common.Result;
import com.zl.d39.document.vectorstore.PgVectorStoreService;
import com.zl.d39.document.vectorstore.dto.VectorMatchView;
import com.zl.d39.document.vectorstore.dto.VectorSearchRequest;
import com.zl.d39.document.vectorstore.dto.VectorStatsView;
import com.zl.d39.document.vectorstore.dto.VectorWriteRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * pgvector 向量存储入口：基础写入、相似度检索与统计。
 *
 * <p>HTTP 统一返回 {@link Result}，业务结果通过 code 区分；不实现 RAG 问答，
 * 仅提供向量存储基础能力的可调用接口。</p>
 *
 * <p>D39 P3：raw 三端点定级 ADMIN_ONLY（endpoint-inventory，D4 裁决）——enabled 时
 * 一律校验服务端角色（只信 AuthContext，不信任何客户端字段，H3）；
 * disabled 时 AccessPolicyService Bean 不存在 → 校验跳过，与既有行为零差异（H1）。</p>
 */
@RestController
@RequestMapping("/api/v1/vector-store")
public class PgVectorController {

    private final PgVectorStoreService service;

    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    public PgVectorController(PgVectorStoreService service,
                              ObjectProvider<AccessPolicyService> accessPolicyProvider) {
        this.service = service;
        this.accessPolicyProvider = accessPolicyProvider;
    }

    /** enabled 时要求服务端 ADMIN 身份；disabled 零差异跳过（无上下文 fail-closed 抛 4030）。 */
    private void requireAdmin() {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy != null) {
            policy.requireAdmin(policy.requireCurrent());
        }
    }

    /**
     * 真实向量写入。
     */
    @PostMapping("/documents")
    public Result<Integer> write(@Valid @RequestBody VectorWriteRequest request) {
        requireAdmin();
        return Result.success(service.addItems(request.getItems()));
    }

    /**
     * 真实余弦相似度检索。
     */
    @PostMapping("/search")
    public Result<List<VectorMatchView>> search(@Valid @RequestBody VectorSearchRequest request) {
        requireAdmin();
        return Result.success(service.search(request));
    }

    /**
     * 向量表统计。
     */
    @GetMapping("/stats")
    public Result<VectorStatsView> stats() {
        requireAdmin();
        return Result.success(new VectorStatsView(service.qualifiedTable(), service.count()));
    }
}
