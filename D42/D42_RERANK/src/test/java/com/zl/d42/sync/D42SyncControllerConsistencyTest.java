package com.zl.d42.sync;

import com.zl.d42.access.AccessDeniedException;
import com.zl.d42.access.AccessPolicyService;
import com.zl.d42.access.AuthContext;
import com.zl.d42.access.AuthRole;
import com.zl.d42.common.Result;
import com.zl.d42.document.sync.dto.ConsistencyCheckRequest;
import com.zl.d42.document.sync.service.AdminAuditService;
import com.zl.d42.document.sync.service.DocumentSyncService;
import com.zl.d42.document.sync.service.SyncRetryRunner;
import com.zl.d42.document.vectorindex.dto.ReconciliationReportView;
import com.zl.d42.document.vectorindex.service.VectorReconciliationService;
import com.zl.d42.document.sync.D42SyncController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C01/C13/C14：管理面单测——非 dryRun 前缀强制校验（H6 夹具域隔离）、
 * disabled 零门禁零审计（H1）、DENIED 双分支留痕（H7）。
 */
class D42SyncControllerConsistencyTest {

    private DocumentSyncService syncService;
    private SyncRetryRunner retryRunner;
    private VectorReconciliationService reconciliationService;
    private AccessPolicyService policy;
    private AdminAuditService audit;
    private ObjectProvider<AccessPolicyService> policyProvider;
    private ObjectProvider<AdminAuditService> auditProvider;
    private D42SyncController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        syncService = mock(DocumentSyncService.class);
        retryRunner = mock(SyncRetryRunner.class);
        reconciliationService = mock(VectorReconciliationService.class);
        policy = mock(AccessPolicyService.class);
        audit = mock(AdminAuditService.class);
        policyProvider = mock(ObjectProvider.class);
        auditProvider = mock(ObjectProvider.class);
        controller = new D42SyncController(syncService, retryRunner, reconciliationService,
                policyProvider, auditProvider);
    }

    private void enableGating() {
        when(policyProvider.getIfAvailable()).thenReturn(policy);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
    }

    @Test
    @DisplayName("修复模式（dryRun=false）缺 prefix → 4001 拒绝，reconcile 零调用（防全域误修复）")
    void repairRequiresPrefix() {
        when(policyProvider.getIfAvailable()).thenReturn(null);
        ConsistencyCheckRequest req = new ConsistencyCheckRequest();
        req.setDryRun(false);
        req.setDocumentPrefix("  ");

        Result<ReconciliationReportView> result = controller.consistency(req);
        assertEquals(4001, result.getCode());
        verify(reconciliationService, never()).reconcile(anyBoolean(), anyString());
    }

    @Test
    @DisplayName("dryRun 默认（请求体 null）→ 以空前缀演练，不触发校验拒绝")
    void dryRunDefaultsPass() {
        when(policyProvider.getIfAvailable()).thenReturn(null);
        when(reconciliationService.reconcile(eq(true), anyString()))
                .thenReturn(new ReconciliationReportView());

        Result<ReconciliationReportView> result = controller.consistency(null);
        assertEquals(200, result.getCode()); // ResultCode.SUCCESS=200
        verify(reconciliationService).reconcile(true, "");
    }

    @Test
    @DisplayName("修复模式带 prefix → 透传 reconcile(false, prefix)（H4 走管线核查入口）")
    void repairPassesPrefix() {
        when(policyProvider.getIfAvailable()).thenReturn(null);
        when(reconciliationService.reconcile(eq(false), anyString()))
                .thenReturn(new ReconciliationReportView());
        ConsistencyCheckRequest req = new ConsistencyCheckRequest();
        req.setDryRun(false);
        req.setDocumentPrefix("d42-");

        Result<ReconciliationReportView> result = controller.consistency(req);
        assertEquals(200, result.getCode()); // ResultCode.SUCCESS=200
        verify(reconciliationService).reconcile(false, "d42-");
    }

    @Test
    @DisplayName("enabled + 非 ADMIN → AccessDenied(ADMIN_REQUIRED) 上抛且 DENIED 审计留痕（C14/C15）")
    void deniedTrailWritten() {
        enableGating();
        AuthContext userCtx = new AuthContext("userA1", "T1", "D1", Set.of("DEV-A"), AuthRole.USER);
        when(policy.requireCurrent()).thenReturn(userCtx);
        org.mockito.Mockito.doThrow(new AccessDeniedException(AccessDeniedException.Reason.ADMIN_REQUIRED))
                .when(policy).requireAdmin(userCtx);
        ConsistencyCheckRequest req = new ConsistencyCheckRequest();

        assertThrows(AccessDeniedException.class, () -> controller.status("d42-doc-a"));
        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("sync.status"),
                eq("d42-doc-a"), eq(0), eq(AdminAuditService.RESULT_DENIED),
                eq("ADMIN_REQUIRED"));
        verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), eq(AdminAuditService.RESULT_ALLOWED), anyString());
    }

    @Test
    @DisplayName("enabled + ADMIN → ALLOWED 留痕后放行")
    void adminAllowedTrail() {
        enableGating();
        AuthContext adminCtx = new AuthContext("adminA1", "T1", "D-HQ", Set.of(), AuthRole.ADMIN);
        when(policy.requireCurrent()).thenReturn(adminCtx);
        when(syncService.listStatus("d42-doc-a")).thenReturn(java.util.List.of());

        Result<?> result = controller.status("d42-doc-a");
        assertEquals(200, result.getCode()); // ResultCode.SUCCESS=200
        verify(audit).record(eq("adminA1"), eq("T1"), eq("ADMIN"), eq("sync.status"),
                eq("d42-doc-a"), eq(0), eq(AdminAuditService.RESULT_ALLOWED), eq(""));
    }

    @Test
    @DisplayName("disabled（provider 空）→ 门禁与审计整体跳过：无凭据可查询（H1 零差异，C14 disabled 分支）")
    void disabledZeroGating() {
        when(policyProvider.getIfAvailable()).thenReturn(null);
        when(auditProvider.getIfAvailable()).thenReturn(null);
        when(syncService.listStatus(any())).thenReturn(java.util.List.of());

        assertEquals(200, controller.status(null).getCode());
        verify(audit, never()).record(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
