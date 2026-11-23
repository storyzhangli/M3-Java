package com.zl.d40.document.sync;

import com.zl.d40.document.sync.mapper.DocumentSyncMapper;
import com.zl.d40.document.sync.model.SyncOperation;
import com.zl.d40.document.sync.model.SyncReasonCode;
import com.zl.d40.document.sync.model.SyncStatus;
import com.zl.d40.document.sync.po.DocumentSyncPO;
import com.zl.d40.document.sync.service.DocumentSyncService;
import com.zl.d40.document.vectorindex.config.VectorIndexProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C01：sync 三态状态机迁移单测（KP4 冻结 transitions 全路径，Mockito 纯单测零库依赖）。
 */
class DocumentSyncServiceStateMachineTest {

    private DocumentSyncMapper mapper;
    private VectorIndexProperties properties;
    private DocumentSyncService service;

    @BeforeEach
    void setUp() {
        mapper = mock(DocumentSyncMapper.class);
        properties = new VectorIndexProperties();
        properties.setMaxAttempts(3);
        properties.setRetryBackoffMs(500);
        service = new DocumentSyncService(mapper, properties);
    }

    private static DocumentSyncPO row(SyncStatus status, SyncReasonCode reason, int attempt) {
        DocumentSyncPO po = new DocumentSyncPO();
        po.setId(1L);
        po.setDocumentId("d40-doc-a");
        po.setOperation(SyncOperation.DELETE.name());
        po.setVersion(SyncOperation.ALL_VERSIONS);
        po.setSyncStatus(status.name());
        po.setReasonCode(reason == null ? "" : reason.name());
        po.setAttemptCount(attempt);
        return po;
    }

    @Test
    @DisplayName("markPending：首见事件 attempt=0；重复事件保留行内 attempt（耗尽判定不被清口）")
    void markPendingPreservesAttempt() {
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt())).thenReturn(null);
        service.markPending("d40-doc-a", SyncOperation.DELETE, null, "hash1");
        ArgumentCaptor<DocumentSyncPO> cap = ArgumentCaptor.forClass(DocumentSyncPO.class);
        verify(mapper).upsertOnNaturalKey(cap.capture());
        DocumentSyncPO po = cap.getValue();
        assertEquals(SyncStatus.PENDING.name(), po.getSyncStatus());
        assertEquals(0, po.getAttemptCount());
        assertEquals(SyncOperation.ALL_VERSIONS, po.getVersion());

        DocumentSyncPO existing = row(SyncStatus.FAILED, SyncReasonCode.PG_UNREACHABLE, 2);
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt())).thenReturn(existing);
        service.markPending("d40-doc-a", SyncOperation.DELETE, null, "hash1");
        verify(mapper, org.mockito.Mockito.times(2)).upsertOnNaturalKey(cap.capture());
        assertEquals(2, cap.getAllValues().get(cap.getAllValues().size() - 1).getAttemptCount());
    }

    @Test
    @DisplayName("markFailed：attempt+1 ∧ 退避落库；未达 max 保留原原因码")
    void markFailedIncrementsAttempt() {
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt()))
                .thenReturn(row(SyncStatus.PENDING, null, 1));
        SyncReasonCode effective = service.markFailed("d40-doc-a", SyncOperation.DELETE, null,
                SyncReasonCode.PG_UNREACHABLE, "hash1");
        assertEquals(SyncReasonCode.PG_UNREACHABLE, effective);
        ArgumentCaptor<DocumentSyncPO> cap = ArgumentCaptor.forClass(DocumentSyncPO.class);
        verify(mapper).upsertOnNaturalKey(cap.capture());
        DocumentSyncPO po = cap.getValue();
        assertEquals(SyncStatus.FAILED.name(), po.getSyncStatus());
        assertEquals(2, po.getAttemptCount());
        assertNotNull(po.getNextRetryAt());
        assertTrue(po.getNextRetryAt().isAfter(LocalDateTime.now().minusSeconds(1)));
    }

    @Test
    @DisplayName("markFailed：attempt 达 maxAttempts 改写 RETRY_EXHAUSTED（KP4 裁决 2）")
    void markFailedRewritesExhausted() {
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt()))
                .thenReturn(row(SyncStatus.FAILED, SyncReasonCode.PG_UNREACHABLE, 2));
        SyncReasonCode effective = service.markFailed("d40-doc-a", SyncOperation.DELETE, null,
                SyncReasonCode.PG_DELETE_FAILED, "hash1");
        assertEquals(SyncReasonCode.RETRY_EXHAUSTED, effective);
    }

    @Test
    @DisplayName("claimForRetry：手动 ∧ 耗尽 → attempt 重置 0；PENDING 由 updateById 落库")
    void claimManualResetsExhausted() {
        DocumentSyncPO exhausted = row(SyncStatus.FAILED, SyncReasonCode.RETRY_EXHAUSTED, 3);
        DocumentSyncPO claimed = service.claimForRetry(exhausted, true);
        assertNotNull(claimed);
        assertEquals(SyncStatus.PENDING.name(), claimed.getSyncStatus());
        assertEquals(0, claimed.getAttemptCount());
        assertEquals("", claimed.getReasonCode());
        assertNull(claimed.getNextRetryAt());
        verify(mapper).updateById(claimed);
    }

    @Test
    @DisplayName("claimForRetry：自动重试未耗尽保留 attempt（先捕获后清空回归——顺序 bug 哨兵）")
    void claimAutoKeepsAttempt() {
        DocumentSyncPO failed = row(SyncStatus.FAILED, SyncReasonCode.PG_UNREACHABLE, 1);
        DocumentSyncPO claimed = service.claimForRetry(failed, false);
        assertNotNull(claimed);
        assertEquals(1, claimed.getAttemptCount());
        assertEquals("", claimed.getReasonCode());
    }

    @Test
    @DisplayName("claimForRetry：非 FAILED（PENDING/SYNCED）与 null → no-op 返回 null 不写库")
    void claimRejectsNonFailed() {
        assertNull(service.claimForRetry(null, true));
        assertNull(service.claimForRetry(row(SyncStatus.PENDING, null, 0), true));
        assertNull(service.claimForRetry(row(SyncStatus.SYNCED, null, 1), true));
        verify(mapper, never()).updateById(any(DocumentSyncPO.class));
    }

    @Test
    @DisplayName("markSynced：终态清空原因码与 next_retry_at，attempt 保留事实值")
    void markSyncedTerminal() {
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt()))
                .thenReturn(row(SyncStatus.PENDING, null, 2));
        service.markSynced("d40-doc-a", SyncOperation.DELETE, null, "hash1");
        ArgumentCaptor<DocumentSyncPO> cap = ArgumentCaptor.forClass(DocumentSyncPO.class);
        verify(mapper).upsertOnNaturalKey(cap.capture());
        DocumentSyncPO po = cap.getValue();
        assertEquals(SyncStatus.SYNCED.name(), po.getSyncStatus());
        assertEquals("", po.getReasonCode());
        assertEquals(2, po.getAttemptCount());
        assertNull(po.getNextRetryAt());
    }

    @Test
    @DisplayName("UPDATE_CLEANUP 自然键携带目标版本号（与 DELETE 行不互踩）")
    void updateCleanupUsesExplicitVersion() {
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt())).thenReturn(null);
        service.markPending("d40-doc-up", SyncOperation.UPDATE_CLEANUP, 1, "hash2");
        verify(mapper).selectByNaturalKey(eq("d40-doc-up"),
                eq(SyncOperation.UPDATE_CLEANUP.name()), eq(1));
    }
}
