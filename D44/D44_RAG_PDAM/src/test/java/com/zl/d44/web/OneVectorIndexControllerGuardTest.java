package com.zl.d44.web;

import com.zl.d44.access.AccessDeniedException;
import com.zl.d44.access.AccessPolicyService;
import com.zl.d44.access.AuthContext;
import com.zl.d44.access.AuthRole;
import com.zl.d44.document.sync.service.AdminAuditService;
import com.zl.d44.one.index.OneDocumentVectorIndexService;
import com.zl.d44.one.index.OneVectorReconciliationService;
import com.zl.d44.one.index.dto.OneReconciliationReportView;
import com.zl.d44.one.index.dto.OneVectorIndexRequest;
import com.zl.d44.one.index.dto.OneVectorIndexResultView;
import com.zl.d44.one.web.OneVectorIndexController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * FIX（G4 收口）守卫单测：one_ 轨 3 写面（index/delete/reconcile）ADMIN-only，
 * guard 先于 service 调用；读面直通；disabled（provider 空）零行为差异（H7）。
 */
class OneVectorIndexControllerGuardTest {

    private OneDocumentVectorIndexService indexService;
    private OneVectorReconciliationService reconciliationService;
    private AccessPolicyService policy;
    private AdminAuditService audit;
    private ObjectProvider<AccessPolicyService> policyProvider;
    private ObjectProvider<AdminAuditService> auditProvider;
    private OneVectorIndexController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        indexService = mock(OneDocumentVectorIndexService.class);
        reconciliationService = mock(OneVectorReconciliationService.class);
        policy = mock(AccessPolicyService.class);
        audit = mock(AdminAuditService.class);
        policyProvider = mock(ObjectProvider.class);
        auditProvider = mock(ObjectProvider.class);
        controller = new OneVectorIndexController(indexService, reconciliationService,
                policyProvider, auditProvider);
    }

    private void enableGating() {
        when(policyProvider.getIfAvailable()).thenReturn(policy);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
    }

    private void denyNonAdmin(AuthContext ctx) {
        when(policy.requireCurrent()).thenReturn(ctx);
        doThrow(new AccessDeniedException(AccessDeniedException.Reason.ADMIN_REQUIRED))
                .when(policy).requireAdmin(ctx);
    }

    private static OneVectorIndexRequest indexReq() {
        OneVectorIndexRequest r = new OneVectorIndexRequest();
        r.setDocumentId("d44-guard-one");
        r.setVersion(1);
        r.setTitle("guard");
        OneVectorIndexRequest.ChunkPayload p = new OneVectorIndexRequest.ChunkPayload();
        p.setChunkIndex(0);
        p.setContent("hello");
        p.setGlobalStart(0);
        p.setGlobalEnd(5);
        r.setChunks(List.of(p));
        return r;
    }

    @Test
    @DisplayName("enabled + USER index → DENIED 审计且 indexService 零调用（guard 先于 service：不触达 Embedding）")
    void indexDeniedBeforeService() {
        enableGating();
        AuthContext userCtx = new AuthContext("userA1", "T1", "D1", Set.of("DEV-A"), AuthRole.USER);
        denyNonAdmin(userCtx);

        assertThrows(AccessDeniedException.class, () -> controller.index(indexReq()));
        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("one.vector-index.index"),
                eq("d44-guard-one"), eq(0), eq(AdminAuditService.RESULT_DENIED), eq("ADMIN_REQUIRED"));
        verify(indexService, never()).indexChunks(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("enabled + USER delete/reconcile → 各自 DENIED 审计，service 零调用（reconcile dryRun 亦先 guard）")
    void deleteAndReconcileDenied() {
        enableGating();
        AuthContext userCtx = new AuthContext("userA1", "T1", "D1", Set.of("DEV-A"), AuthRole.USER);
        denyNonAdmin(userCtx);

        assertThrows(AccessDeniedException.class, () -> controller.delete("d44-guard-one"));
        assertThrows(AccessDeniedException.class, () -> controller.reconcile(true));

        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("one.vector-index.delete"),
                eq("d44-guard-one"), eq(0), eq(AdminAuditService.RESULT_DENIED), eq("ADMIN_REQUIRED"));
        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("one.vector-index.reconcile"),
                eq(""), eq(0), eq(AdminAuditService.RESULT_DENIED), eq("ADMIN_REQUIRED"));
        verify(indexService, never()).deleteByDocument(any());
        verify(indexService, never()).listChunks(any());
        verify(reconciliationService, never()).reconcile(anyBoolean());
    }

    @Test
    @DisplayName("enabled + ADMIN → ALLOWED 审计后放行三写面")
    void adminAllowedAllWriteFaces() {
        enableGating();
        AuthContext adminCtx = new AuthContext("adminA1", "T1", "D-HQ", Set.of(), AuthRole.ADMIN);
        when(policy.requireCurrent()).thenReturn(adminCtx);
        when(indexService.indexChunks(any(), any(), any(), any(), any()))
                .thenReturn(new OneVectorIndexResultView());
        when(indexService.listChunks("d44-guard-one")).thenReturn(List.of());
        when(reconciliationService.reconcile(true)).thenReturn(new OneReconciliationReportView());

        assertEquals(200, controller.index(indexReq()).getCode());
        assertEquals(200, controller.delete("d44-guard-one").getCode());
        assertEquals(200, controller.reconcile(true).getCode());
        verify(audit, times(3)).record(eq("adminA1"), eq("T1"), eq("ADMIN"), anyString(),
                anyString(), eq(0), eq(AdminAuditService.RESULT_ALLOWED), eq(""));
    }

    @Test
    @DisplayName("读面不受门禁：status/chunks 零 requireCurrent 零审计")
    void readFacesUngated() {
        enableGating();
        when(indexService.getStatus("d44-guard-one")).thenReturn(List.of());
        when(indexService.listChunks("d44-guard-one")).thenReturn(List.of());

        assertEquals(200, controller.status("d44-guard-one").getCode());
        assertEquals(200, controller.chunks("d44-guard-one").getCode());
        verify(policy, never()).requireCurrent();
        verify(audit, never()).record(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("disabled（provider 空）→ USER 亦可写（H1 零差异，one_ 臂独立于 access 开关）")
    void disabledZeroGating() {
        when(policyProvider.getIfAvailable()).thenReturn(null);
        when(auditProvider.getIfAvailable()).thenReturn(null);
        when(indexService.indexChunks(any(), any(), any(), any(), any()))
                .thenReturn(new OneVectorIndexResultView());

        assertEquals(200, controller.index(indexReq()).getCode());
        verify(indexService).indexChunks(eq("d44-guard-one"), eq(1), any(), eq("guard"), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }
}
