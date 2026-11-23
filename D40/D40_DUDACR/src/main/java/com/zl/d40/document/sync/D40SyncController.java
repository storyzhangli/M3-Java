package com.zl.d40.document.sync;

import com.zl.d40.access.AccessDeniedException;
import com.zl.d40.access.AccessPolicyService;
import com.zl.d40.access.AuthContext;
import com.zl.d40.common.Result;
import com.zl.d40.document.sync.dto.ConsistencyCheckRequest;
import com.zl.d40.document.sync.dto.SyncRetryRequest;
import com.zl.d40.document.sync.dto.SyncRetryResultView;
import com.zl.d40.document.sync.po.DocumentSyncPO;
import com.zl.d40.document.sync.service.AdminAuditService;
import com.zl.d40.document.sync.service.DocumentSyncService;
import com.zl.d40.document.sync.service.SyncRetryRunner;
import com.zl.d40.document.vectorindex.dto.ReconciliationReportView;
import com.zl.d40.document.vectorindex.service.VectorReconciliationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 一致性同步管理面（P1 新建；H7：enabled 时全部端点 ADMIN-only + 审计留痕）。
 *
 * <p>能力：失败可查询（status）、可重试（retry，手动恒可用 H2）、可核查（consistency，
 * dryRun 默认 true，修复只走管线 H4）。</p>
 *
 * <p>门禁与审计均走 ObjectProvider 双可选依赖：{@code app.access.enabled=false}（默认）时
 * {@link AccessPolicyService}/{@link AdminAuditService} 不装配 → 校验与留痕整体跳过 →
 * 开发态零差异零写（H1/H2，C05/C14 断言口径）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sync")
public class D40SyncController {

    private final DocumentSyncService syncService;

    private final SyncRetryRunner retryRunner;

    private final VectorReconciliationService reconciliationService;

    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    private final ObjectProvider<AdminAuditService> auditProvider;

    public D40SyncController(DocumentSyncService syncService,
                             SyncRetryRunner retryRunner,
                             VectorReconciliationService reconciliationService,
                             ObjectProvider<AccessPolicyService> accessPolicyProvider,
                             ObjectProvider<AdminAuditService> auditProvider) {
        this.syncService = syncService;
        this.retryRunner = retryRunner;
        this.reconciliationService = reconciliationService;
        this.accessPolicyProvider = accessPolicyProvider;
        this.auditProvider = auditProvider;
    }

    /**
     * ADMIN 校验 + 双分支审计（DENIED 也留痕；无凭据场景 actor=ANONYMOUS）。
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

    /** 同步状态查询（失败可查询）：documentId 空=全部行。 */
    @GetMapping("/status")
    public Result<List<DocumentSyncPO>> status(
            @RequestParam(value = "documentId", required = false) String documentId) {
        guardAdmin("sync.status", documentId == null ? "" : documentId, 0);
        return Result.success(syncService.listStatus(documentId));
    }

    /** 手动重试（ADMIN；恒可用 H2；耗尽行 claim 时 attempt 重置续试）。 */
    @PostMapping("/retry")
    public Result<List<SyncRetryResultView>> retry(@Valid @RequestBody(required = false)
                                                   SyncRetryRequest request) {
        String documentId = request == null ? null : request.getDocumentId();
        guardAdmin("sync.retry", documentId == null ? "" : documentId, 0);
        return Result.success(retryRunner.retryManual(documentId));
    }

    /**
     * 一致性核查：dryRun 默认 true（H4）；修复动作全部走既有管线，
     * 且<b>必须</b>携带非空 documentPrefix（H6 夹具域隔离——全局非 dryRun 会把
     * 跨 Dxx 遗留关联行重建进当前 PG 表，破坏冻结基线，见 reconcile javadoc 风险提示）。
     */
    @PostMapping("/consistency")
    public Result<ReconciliationReportView> consistency(
            @Valid @RequestBody(required = false) ConsistencyCheckRequest request) {
        ConsistencyCheckRequest safe = request == null ? new ConsistencyCheckRequest() : request;
        String prefix = safe.getDocumentPrefix() == null ? "" : safe.getDocumentPrefix().trim();
        boolean repair = Boolean.FALSE.equals(safe.getDryRun());
        if (repair && prefix.isEmpty()) {
            return Result.fail(4001, "非 dryRun 修复必须指定非空 documentPrefix（夹具域隔离，防跨遗留行误修复）");
        }
        guardAdmin("sync.consistency", prefix, 0);
        ReconciliationReportView report = reconciliationService.reconcile(!repair, prefix);
        return Result.success(report);
    }
}
