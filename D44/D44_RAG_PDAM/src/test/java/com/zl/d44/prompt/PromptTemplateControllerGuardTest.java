package com.zl.d44.prompt;

import com.zl.d44.access.AccessDeniedException;
import com.zl.d44.access.AccessPolicyService;
import com.zl.d44.access.AuthContext;
import com.zl.d44.access.AuthRole;
import com.zl.d44.document.sync.service.AdminAuditService;
import com.zl.d44.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d44.prompt.dto.TemplateStatusUpdateRequest;
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
 * FIX（G2 收口）守卫单测：PromptTemplate 4 写面 ADMIN-only + 双分支审计；
 * 读面不受门禁；disabled（provider 空）零行为差异（H7）。
 * 脚手架复用 DxxSyncControllerConsistencyTest 模式（纯 Mockito，无 Spring 上下文）。
 */
class PromptTemplateControllerGuardTest {

    private PromptTemplateService service;
    private AccessPolicyService policy;
    private AdminAuditService audit;
    private ObjectProvider<AccessPolicyService> policyProvider;
    private ObjectProvider<AdminAuditService> auditProvider;
    private PromptTemplateController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(PromptTemplateService.class);
        policy = mock(AccessPolicyService.class);
        audit = mock(AdminAuditService.class);
        policyProvider = mock(ObjectProvider.class);
        auditProvider = mock(ObjectProvider.class);
        controller = new PromptTemplateController(service, policyProvider, auditProvider);
    }

    private void enableGating() {
        when(policyProvider.getIfAvailable()).thenReturn(policy);
        when(auditProvider.getIfAvailable()).thenReturn(audit);
    }

    private static PromptTemplateUpsertRequest req(String templateId) {
        PromptTemplateUpsertRequest r = new PromptTemplateUpsertRequest();
        r.setTemplateId(templateId);
        r.setName("guard-test");
        r.setScene("customer_service");
        r.setContent("hello");
        return r;
    }

    private void denyNonAdmin(AuthContext ctx) {
        when(policy.requireCurrent()).thenReturn(ctx);
        doThrow(new AccessDeniedException(AccessDeniedException.Reason.ADMIN_REQUIRED))
                .when(policy).requireAdmin(ctx);
    }

    @Test
    @DisplayName("enabled + USER 创建模板 → AccessDenied 上抛 + DENIED 审计（prompts.create），service 零调用")
    void createDeniedByUser() {
        enableGating();
        AuthContext userCtx = new AuthContext("userA1", "T1", "D1", Set.of("DEV-A"), AuthRole.USER);
        denyNonAdmin(userCtx);

        assertThrows(AccessDeniedException.class, () -> controller.create(req("d44-guard-pt")));
        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("prompts.create"),
                eq("d44-guard-pt"), eq(0), eq(AdminAuditService.RESULT_DENIED), eq("ADMIN_REQUIRED"));
        verify(service, never()).create(any());
    }

    @Test
    @DisplayName("enabled + ADMIN 创建 → ALLOWED 审计后放行，service 实调")
    void createAllowedForAdmin() {
        enableGating();
        AuthContext adminCtx = new AuthContext("adminA1", "T1", "D-HQ", Set.of(), AuthRole.ADMIN);
        when(policy.requireCurrent()).thenReturn(adminCtx);
        when(service.create(any())).thenReturn(null);

        assertEquals(200, controller.create(req("d44-guard-pt")).getCode());
        verify(audit).record(eq("adminA1"), eq("T1"), eq("ADMIN"), eq("prompts.create"),
                eq("d44-guard-pt"), eq(0), eq(AdminAuditService.RESULT_ALLOWED), eq(""));
        verify(service).create(any());
    }

    @Test
    @DisplayName("写面全覆盖：update/status/delete 各自守卫 action 正确（prompts.update/status/delete）")
    void allWriteFacesGuarded() {
        enableGating();
        AuthContext userCtx = new AuthContext("userA1", "T1", "D1", Set.of("DEV-A"), AuthRole.USER);
        denyNonAdmin(userCtx);
        TemplateStatusUpdateRequest st = new TemplateStatusUpdateRequest();
        st.setStatus("DISABLED");

        assertThrows(AccessDeniedException.class, () -> controller.update("pt-1", req("pt-1")));
        assertThrows(AccessDeniedException.class, () -> controller.changeStatus("pt-1", st));
        assertThrows(AccessDeniedException.class, () -> controller.delete("pt-1"));

        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("prompts.update"),
                eq("pt-1"), eq(0), eq(AdminAuditService.RESULT_DENIED), eq("ADMIN_REQUIRED"));
        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("prompts.status"),
                eq("pt-1"), eq(0), eq(AdminAuditService.RESULT_DENIED), eq("ADMIN_REQUIRED"));
        verify(audit).record(eq("userA1"), eq("T1"), eq("USER"), eq("prompts.delete"),
                eq("pt-1"), eq(0), eq(AdminAuditService.RESULT_DENIED), eq("ADMIN_REQUIRED"));
        verify(service, never()).update(any(), any());
        verify(service, never()).changeStatus(any(), any());
        verify(service, never()).logicalDelete(any());
    }

    @Test
    @DisplayName("读面不受门禁（AUTH-ONLY 分级）：enabled + USER 列表/详情直通，零审计")
    void readFacesUngated() {
        enableGating();
        when(service.list(any(), any(), any())).thenReturn(List.of());
        when(service.detail("pt-1")).thenReturn(null);
        when(service.listEnabledRoles()).thenReturn(List.of());

        assertEquals(200, controller.list(null, null, null).getCode());
        assertEquals(200, controller.detail("pt-1").getCode());
        assertEquals(200, controller.roles().getCode());
        verify(policy, never()).requireCurrent();
        verify(audit, never()).record(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("disabled（provider 空）→ 门禁与审计整体跳过：USER 也能写（H1 零差异回归证明）")
    void disabledZeroGating() {
        when(policyProvider.getIfAvailable()).thenReturn(null);
        when(auditProvider.getIfAvailable()).thenReturn(null);
        when(service.create(any())).thenReturn(null);

        assertEquals(200, controller.create(req("d44-guard-pt")).getCode());
        verify(audit, never()).record(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }
}
