package com.zl.d43.one.web;

import com.zl.d43.access.AccessDeniedException;
import com.zl.d43.access.AccessPolicyService;
import com.zl.d43.access.AuthContext;
import com.zl.d43.common.Result;
import com.zl.d43.document.chunk.model.Chunk;
import com.zl.d43.document.chunk.model.ChunkStrategy;
import com.zl.d43.document.sync.service.AdminAuditService;
import com.zl.d43.one.index.OneDocumentVectorIndexService;
import com.zl.d43.one.index.OneVectorReconciliationService;
import com.zl.d43.one.index.dto.OneReconciliationReportView;
import com.zl.d43.one.index.dto.OneVectorIndexRequest;
import com.zl.d43.one.index.dto.OneVectorIndexResultView;
import com.zl.d43.one.index.po.OneDocumentChunkIndexPO;
import com.zl.d43.one.index.po.OneDocumentVectorIndexPO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * one_ 轨向量索引编排入口（阿里百炼远程 Embedding，独立于 local 轨端点）。
 *
 * <p>仅在 app.one.embedding.enabled=true 时装配；统一返回 {@link Result}；
 * 写操作基于稳定 ID 与数据库约束，重复调用安全。D43 FIX-003（G4 收口）：
 * index/delete/reconcile 三写面在 app.access.enabled=true 时 ADMIN-only 守卫
 * + 双分支审计（同型 VectorIndexController，guard 先于 service 调用）；
 * ObjectProvider 装配保证 access 关闭态零行为差异。</p>
 */
@RestController
@RequestMapping("/api/v1/one/vector-index")
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneVectorIndexController {

    private final OneDocumentVectorIndexService indexService;
    private final OneVectorReconciliationService reconciliationService;
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;
    private final ObjectProvider<AdminAuditService> auditProvider;

    public OneVectorIndexController(OneDocumentVectorIndexService indexService,
                                    OneVectorReconciliationService reconciliationService,
                                    ObjectProvider<AccessPolicyService> accessPolicyProvider,
                                    ObjectProvider<AdminAuditService> auditProvider) {
        this.indexService = indexService;
        this.reconciliationService = reconciliationService;
        this.accessPolicyProvider = accessPolicyProvider;
        this.auditProvider = auditProvider;
    }

    /**
     * FIX-003：ADMIN 校验 + 双分支审计；access bean 缺席（disabled）直通零差异。
     */
    private void guardAdmin(String action, String targetDocumentId) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy == null) {
            return;
        }
        AuthContext context = null;
        try {
            context = policy.requireCurrent();
            policy.requireAdmin(context);
        } catch (AccessDeniedException denied) {
            recordAudit(context, action, targetDocumentId,
                    AdminAuditService.RESULT_DENIED, denied.reason().name());
            throw denied;
        }
        recordAudit(context, action, targetDocumentId, AdminAuditService.RESULT_ALLOWED, "");
    }

    private void recordAudit(AuthContext context, String action, String target,
                             String result, String reasonCode) {
        AdminAuditService audit = auditProvider.getIfAvailable();
        if (audit == null) {
            return;
        }
        audit.record(context == null ? "ANONYMOUS" : context.userId(),
                context == null ? "" : context.tenantId(),
                context == null ? "" : context.role().name(),
                action, target, 0, result, reasonCode);
    }

    /** 直接 Chunk 幂等入库（one_ 轨，真实远程 Embedding）。ADMIN-only（FIX-003）。 */
    @PostMapping("/index")
    public Result<OneVectorIndexResultView> index(@Valid @RequestBody OneVectorIndexRequest request) {
        guardAdmin("one.vector-index.index", request.getDocumentId() == null ? "" : request.getDocumentId());
        List<Chunk> chunks = toChunks(request);
        return Result.success(indexService.indexChunks(request.getDocumentId(),
                request.getVersion(), request.getStrategy(), request.getTitle(), chunks));
    }

    /** 按文档删除全部版本 one_ 向量与关联。ADMIN-only（FIX-003）。 */
    @DeleteMapping("/documents/{documentId}")
    public Result<Integer> delete(@PathVariable String documentId) {
        guardAdmin("one.vector-index.delete", documentId == null ? "" : documentId);
        int before = indexService.listChunks(documentId).size();
        indexService.deleteByDocument(documentId);
        return Result.success(before);
    }

    /** 文档索引状态（全部版本，one_ 表）。 */
    @GetMapping("/documents/{documentId}/status")
    public Result<List<OneDocumentVectorIndexPO>> status(@PathVariable String documentId) {
        return Result.success(indexService.getStatus(documentId));
    }

    /** 文档 Chunk 关联明细（one_ 表）。 */
    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<OneDocumentChunkIndexPO>> chunks(@PathVariable String documentId) {
        return Result.success(indexService.listChunks(documentId));
    }

    /** one_ 表对跨系统一致性核对；默认 dryRun=true 只报告。ADMIN-only（FIX-003，guard 先于 service）。 */
    @PostMapping("/reconcile")
    public Result<OneReconciliationReportView> reconcile(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        guardAdmin("one.vector-index.reconcile", "");
        return Result.success(reconciliationService.reconcile(dryRun));
    }

    private List<Chunk> toChunks(OneVectorIndexRequest request) {
        List<Chunk> chunks = new ArrayList<>(request.getChunks().size());
        ChunkStrategy strategy = request.getStrategy() == null
                ? ChunkStrategy.STRUCTURE
                : ChunkStrategy.valueOf(request.getStrategy());
        for (OneVectorIndexRequest.ChunkPayload payload : request.getChunks()) {
            Chunk chunk = new Chunk();
            chunk.setDocumentId(request.getDocumentId());
            chunk.setTitle(request.getTitle());
            chunk.setVersion(request.getVersion() == null ? 1 : request.getVersion());
            chunk.setStrategy(strategy);
            chunk.setChunkIndex(payload.getChunkIndex());
            chunk.setContent(payload.getContent());
            chunk.setLength(payload.getContent().length());
            chunk.setGlobalStart(payload.getGlobalStart());
            chunk.setGlobalEnd(payload.getGlobalEnd());
            chunks.add(chunk);
        }
        return chunks;
    }
}