package com.zl.d42.document.vectorindex;

import com.zl.d42.access.AccessDeniedException;
import com.zl.d42.access.AccessPolicyService;
import com.zl.d42.access.AuthContext;
import com.zl.d42.common.Result;
import com.zl.d42.document.chunk.model.Chunk;
import com.zl.d42.document.chunk.model.ChunkStrategy;
import com.zl.d42.document.po.DocumentChunkIndexPO;
import com.zl.d42.document.po.DocumentVectorIndexPO;
import com.zl.d42.document.sync.service.AdminAuditService;
import com.zl.d42.document.vectorindex.dto.*;
import com.zl.d42.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d42.document.vectorindex.service.VectorReconciliationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量索引编排入口：幂等入库、版本更新、删除、核对与恢复。
 *
 * <p>统一返回 {@link Result}；所有写操作基于稳定 ID 与数据库约束，
 * 重复调用安全。无鉴权，仅提供本地开发/学习用途。</p>
 *
 * <p><b>P3 写面收口</b>（H7）：enabled 时 5 个<b>写</b>端点
 * （index / indexFromPages / delete / reconcile / recover）ADMIN-only + 审计留痕
 * （DENIED 也留痕）；disabled 时 AccessPolicyService 不装配 → 门禁整体跳过 →
 * 零差异（H1）。读端点（status/chunks）维持 D39 DOC_SCOPE_CHECK 分级不变。</p>
 */
@RestController
@RequestMapping("/api/v1/vector-index")
public class VectorIndexController {

    private final DocumentVectorIndexService indexService;

    private final VectorReconciliationService reconciliationService;

    /** P3：status/chunks 预览端点=DOC_SCOPE_CHECK（inventory）；disabled 零差异（H1）。 */
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    /** P3：写面 ADMIN-only 审计（可选依赖，disabled 时缺席即零留痕）。 */
    private final ObjectProvider<AdminAuditService> auditProvider;

    public VectorIndexController(DocumentVectorIndexService indexService,
                                 VectorReconciliationService reconciliationService,
                                 ObjectProvider<AccessPolicyService> accessPolicyProvider,
                                 ObjectProvider<AdminAuditService> auditProvider) {
        this.indexService = indexService;
        this.reconciliationService = reconciliationService;
        this.accessPolicyProvider = accessPolicyProvider;
        this.auditProvider = auditProvider;
    }

    /** enabled 时文档级读取校验；disabled 跳过。 */
    private void checkReadable(String documentId) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy != null) {
            policy.requireDocument(policy.requireCurrent(), documentId);
        }
    }

    /**
     * P3（H7）：ADMIN 校验 + 双分支审计，语义与 D42SyncController.guardAdmin 一致；
     * disabled 时整体跳过（零差异）。
     */
    private void guardAdmin(String action, String targetDocumentId, int version) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy == null) {
            return;
        }
        AuthContext context = null;
        try {
            context = policy.requireCurrent();
            policy.requireAdmin(context);
        } catch (AccessDeniedException denied) {
            recordAudit(context, action, targetDocumentId, version,
                    AdminAuditService.RESULT_DENIED, denied.reason().name());
            throw denied;
        }
        recordAudit(context, action, targetDocumentId, version,
                AdminAuditService.RESULT_ALLOWED, "");
    }

    private void recordAudit(AuthContext context, String action, String target, int version,
                             String result, String reasonCode) {
        AdminAuditService audit = auditProvider.getIfAvailable();
        if (audit == null) {
            return;
        }
        audit.record(context == null ? "ANONYMOUS" : context.userId(),
                context == null ? "" : context.tenantId(),
                context == null ? "" : context.role().name(),
                action, target, version, result, reasonCode);
    }

    /**
     * 直接 Chunk 幂等入库（不依赖原文页表）。
     */
    @PostMapping("/index")
    public Result<VectorIndexResultView> index(@Valid @RequestBody VectorIndexRequest request) {
        guardAdmin("vector-index.index", request.getDocumentId(),
                request.getVersion() == null ? 0 : request.getVersion());
        List<Chunk> chunks = toChunks(request);
        return Result.success(indexService.indexChunks(request.getDocumentId(),
                request.getVersion(), request.getStrategy(), request.getTitle(), chunks));
    }

    /**
     * 从原文页表加载并切分后入库。
     */
    @PostMapping("/documents/{documentId}/index")
    public Result<VectorIndexResultView> indexFromPages(@PathVariable String documentId,
                                                        @RequestParam(required = false) String strategy) {
        guardAdmin("vector-index.indexFromPages", documentId, 0);
        return Result.success(indexService.indexFromPages(documentId, strategy));
    }

    /**
     * 按文档删除全部版本向量与关联。
     */
    @DeleteMapping("/documents/{documentId}")
    public Result<Integer> delete(@PathVariable String documentId) {
        guardAdmin("vector-index.delete", documentId, 0);
        int before = indexService.listChunks(documentId).size();
        indexService.deleteByDocument(documentId);
        return Result.success(before);
    }

    /**
     * 跨系统一致性核对：默认 dryRun。
     *
     * <p>风险提示：非 dryRun 全域修复会波及跨 Dxx 遗留关联行
     * （详见 {@link VectorReconciliationService#reconcile(boolean)} javadoc）；
     * 带修复域限定的核查请使用 ADMIN 的 /api/v1/sync/consistency。</p>
     */
    @PostMapping("/reconcile")
    public Result<ReconciliationReportView> reconcile(@Valid @RequestBody ReconcileRequest request) {
        guardAdmin("vector-index.reconcile", "", 0);
        return Result.success(reconciliationService.reconcile(Boolean.TRUE.equals(request.getDryRun())));
    }

    /**
     * 手动触发卡住任务恢复。
     */
    @PostMapping("/recover")
    public Result<List<VectorIndexResultView>> recover() {
        guardAdmin("vector-index.recover", "", 0);
        return Result.success(indexService.recoverStuckTasks());
    }

    /**
     * 文档索引状态（全部版本）。
     */
    @GetMapping("/documents/{documentId}/status")
    public Result<List<DocumentVectorIndexPO>> status(@PathVariable String documentId) {
        checkReadable(documentId);
        return Result.success(indexService.getStatus(documentId));
    }

    /**
     * 文档 Chunk 关联明细。
     */
    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<ChunkIndexView>> chunks(@PathVariable String documentId) {
        checkReadable(documentId);
        List<ChunkIndexView> views = new ArrayList<>();
        for (DocumentChunkIndexPO po : indexService.listChunks(documentId)) {
            views.add(ChunkIndexView.from(po));
        }
        return Result.success(views);
    }

    private List<Chunk> toChunks(VectorIndexRequest request) {
        List<Chunk> chunks = new ArrayList<>(request.getChunks().size());
        ChunkStrategy strategy = request.getStrategy() == null
                ? ChunkStrategy.STRUCTURE
                : ChunkStrategy.valueOf(request.getStrategy());
        for (VectorIndexRequest.ChunkPayload payload : request.getChunks()) {
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
