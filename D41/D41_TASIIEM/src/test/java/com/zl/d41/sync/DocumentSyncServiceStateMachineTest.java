package com.zl.d41.sync;

import com.zl.d41.document.sync.mapper.DocumentSyncMapper;
import com.zl.d41.document.sync.model.SyncOperation;
import com.zl.d41.document.sync.model.SyncReasonCode;
import com.zl.d41.document.sync.model.SyncStatus;
import com.zl.d41.document.sync.po.DocumentSyncPO;
import com.zl.d41.document.sync.service.DocumentSyncService;
import com.zl.d41.document.vectorindex.config.VectorIndexProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
        po.setDocumentId("d41-doc-a");
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
        service.markPending("d41-doc-a", SyncOperation.DELETE, null, "hash1");
        ArgumentCaptor<DocumentSyncPO> cap = ArgumentCaptor.forClass(DocumentSyncPO.class);
        verify(mapper).upsertOnNaturalKey(cap.capture());
        DocumentSyncPO po = cap.getValue();
        assertEquals(SyncStatus.PENDING.name(), po.getSyncStatus());
        assertEquals(0, po.getAttemptCount());
        assertEquals(SyncOperation.ALL_VERSIONS, po.getVersion());

        DocumentSyncPO existing = row(SyncStatus.FAILED, SyncReasonCode.PG_UNREACHABLE, 2);
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt())).thenReturn(existing);
        service.markPending("d41-doc-a", SyncOperation.DELETE, null, "hash1");
        verify(mapper, org.mockito.Mockito.times(2)).upsertOnNaturalKey(cap.capture());
        assertEquals(2, cap.getAllValues().get(cap.getAllValues().size() - 1).getAttemptCount());
    }

    @Test
    @DisplayName("markFailed：attempt+1 ∧ 退避落库；未达 max 保留原原因码")
    void markFailedIncrementsAttempt() {
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt()))
                .thenReturn(row(SyncStatus.PENDING, null, 1));
        SyncReasonCode effective = service.markFailed("d41-doc-a", SyncOperation.DELETE, null,
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
        SyncReasonCode effective = service.markFailed("d41-doc-a", SyncOperation.DELETE, null,
                SyncReasonCode.PG_DELETE_FAILED, "hash1");
        assertEquals(SyncReasonCode.RETRY_EXHAUSTED, effective);
    }

    @Test
    @DisplayName("claimForRetry（R02 原子领取）：手动 ∧ 耗尽 → newAttempt=0 条件 UPDATE，affected=1 领取成功")
    void claimManualResetsExhausted() {
        when(mapper.claimFailedAtomically(eq(1L), eq(0), eq(""), any(LocalDateTime.class)))
                .thenReturn(1);
        DocumentSyncPO exhausted = row(SyncStatus.FAILED, SyncReasonCode.RETRY_EXHAUSTED, 3);
        DocumentSyncPO claimed = service.claimForRetry(exhausted, true);
        assertNotNull(claimed);
        assertEquals(SyncStatus.PENDING.name(), claimed.getSyncStatus());
        assertEquals(0, claimed.getAttemptCount());
        assertEquals("", claimed.getReasonCode());
        assertNull(claimed.getNextRetryAt());
        verify(mapper).claimFailedAtomically(eq(1L), eq(0), eq(""), any(LocalDateTime.class));
        verify(mapper, never()).updateById(any(DocumentSyncPO.class));
    }

    @Test
    @DisplayName("claimForRetry（R02）：自动未耗尽保留 attempt（不自增，递增点=markFailed）；先捕获后清空顺序哨兵保留")
    void claimAutoKeepsAttempt() {
        when(mapper.claimFailedAtomically(eq(1L), eq(1), eq(""), any(LocalDateTime.class)))
                .thenReturn(1);
        DocumentSyncPO failed = row(SyncStatus.FAILED, SyncReasonCode.PG_UNREACHABLE, 1);
        DocumentSyncPO claimed = service.claimForRetry(failed, false);
        assertNotNull(claimed);
        assertEquals(1, claimed.getAttemptCount());
        assertEquals("", claimed.getReasonCode());
    }

    @Test
    @DisplayName("claimForRetry（R02）：非 FAILED（PENDING/SYNCED）、null、无 id → no-op 返回 null 不发 UPDATE")
    void claimRejectsNonFailed() {
        assertNull(service.claimForRetry(null, true));
        assertNull(service.claimForRetry(row(SyncStatus.PENDING, null, 0), true));
        assertNull(service.claimForRetry(row(SyncStatus.SYNCED, null, 1), true));
        DocumentSyncPO noId = row(SyncStatus.FAILED, null, 1);
        noId.setId(null);
        assertNull(service.claimForRetry(noId, true));
        verify(mapper, never()).claimFailedAtomically(any(), anyInt(), anyString(), any());
        verify(mapper, never()).updateById(any(DocumentSyncPO.class));
    }

    @Test
    @DisplayName("markSynced：终态清空原因码与 next_retry_at，attempt 保留事实值")
    void markSyncedTerminal() {
        when(mapper.selectByNaturalKey(anyString(), anyString(), anyInt()))
                .thenReturn(row(SyncStatus.PENDING, null, 2));
        service.markSynced("d41-doc-a", SyncOperation.DELETE, null, "hash1");
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
        service.markPending("d41-doc-up", SyncOperation.UPDATE_CLEANUP, 1, "hash2");
        verify(mapper).selectByNaturalKey(eq("d41-doc-up"),
                eq(SyncOperation.UPDATE_CLEANUP.name()), eq(1));
    }
}
