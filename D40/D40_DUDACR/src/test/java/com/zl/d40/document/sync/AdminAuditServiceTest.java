package com.zl.d40.document.sync;

import com.zl.d40.document.sync.mapper.AdminAuditMapper;
import com.zl.d40.document.sync.po.AdminAuditPO;
import com.zl.d40.document.sync.service.AdminAuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C01/C15：审计留痕单测——字段归一化、DENIED 留痕完整性、写失败不阻断业务（旁路面裁决）。
 */
class AdminAuditServiceTest {

    @Test
    @DisplayName("null 参数归一为空串/0（NOT NULL 列零违约）")
    void nullNormalizes() {
        AdminAuditMapper mapper = mock(AdminAuditMapper.class);
        AdminAuditService service = new AdminAuditService(mapper);
        service.record(null, null, null, "sync.retry", null, -3,
                AdminAuditService.RESULT_DENIED, null);
        ArgumentCaptor<AdminAuditPO> cap = ArgumentCaptor.forClass(AdminAuditPO.class);
        verify(mapper).insert(cap.capture());
        AdminAuditPO po = cap.getValue();
        assertEquals("", po.getActorUserId());
        assertEquals("", po.getTenantId());
        assertEquals("", po.getTargetDocumentId());
        assertEquals(0, po.getVersion());
        assertEquals("", po.getReasonCode());
        assertEquals(AdminAuditService.RESULT_DENIED, po.getResult());
    }

    @Test
    @DisplayName("ALLOWED 留痕：actor/tenant/role/action/target 全字段透传")
    void allowedTrail() {
        AdminAuditMapper mapper = mock(AdminAuditMapper.class);
        AdminAuditService service = new AdminAuditService(mapper);
        service.record("adminA1", "T1", "ADMIN", "vector-index.delete", "d40-doc-a", 0,
                AdminAuditService.RESULT_ALLOWED, "");
        ArgumentCaptor<AdminAuditPO> cap = ArgumentCaptor.forClass(AdminAuditPO.class);
        verify(mapper).insert(cap.capture());
        AdminAuditPO po = cap.getValue();
        assertEquals("adminA1", po.getActorUserId());
        assertEquals("ADMIN", po.getRole());
        assertEquals("vector-index.delete", po.getAction());
        assertEquals("d40-doc-a", po.getTargetDocumentId());
        org.junit.jupiter.api.Assertions.assertNotNull(po.getCreatedAt());
    }

    @Test
    @DisplayName("审计表不可达（insert 抛错）→ 吞异常不上抛：旁路留痕不阻断/不回滚业务")
    void auditFailureNeverEscapes() {
        AdminAuditMapper mapper = mock(AdminAuditMapper.class);
        when(mapper.insert(any(AdminAuditPO.class)))
                .thenThrow(new RuntimeException("table d40_admin_audit missing"));
        AdminAuditService service = new AdminAuditService(mapper);
        assertDoesNotThrow(() -> service.record("u", "t", "USER", "a", "", 0, "DENIED", "R"));
    }
}
