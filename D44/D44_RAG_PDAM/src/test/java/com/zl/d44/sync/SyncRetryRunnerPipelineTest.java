package com.zl.d44.sync;

import com.zl.d44.document.sync.dto.SyncRetryResultView;
import com.zl.d44.document.sync.model.SyncOperation;
import com.zl.d44.document.sync.model.SyncReasonCode;
import com.zl.d44.document.sync.model.SyncStatus;
import com.zl.d44.document.sync.po.DocumentSyncPO;
import com.zl.d44.document.sync.service.DocumentSyncService;
import com.zl.d44.document.sync.service.SyncRetryRunner;
import com.zl.d44.document.vectorindex.service.DocumentVectorIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C01：重试编排路由单测——操作→管线映射与 fail-closed/异常不外溢语义（H4）。
 */
class SyncRetryRunnerPipelineTest {

    private DocumentSyncService syncService;
    private DocumentVectorIndexService indexService;
    private SyncRetryRunner runner;

    @BeforeEach
    void setUp() {
        syncService = mock(DocumentSyncService.class);
        indexService = mock(DocumentVectorIndexService.class);
        runner = new SyncRetryRunner(syncService, indexService);
    }

    private static DocumentSyncPO failed(SyncOperation op, int version) {
        DocumentSyncPO po = new DocumentSyncPO();
        po.setId(9L);
        po.setDocumentId("d44-doc-x");
        po.setOperation(op.name());
        po.setVersion(version);
        po.setSyncStatus(SyncStatus.FAILED.name());
        po.setReasonCode(SyncReasonCode.PG_UNREACHABLE.name());
        po.setAttemptCount(1);
        return po;
    }

    private static DocumentSyncPO converged(SyncOperation op, int version) {
        DocumentSyncPO po = failed(op, version);
        po.setSyncStatus(SyncStatus.SYNCED.name());
        po.setReasonCode("");
        return po;
    }

    @Test
    @DisplayName("DELETE 行 → deleteByDocument 全闭环重跑；UPDATE_CLEANUP → retryCleanupVersion")
    void routesByOperation() {
        DocumentSyncPO del = failed(SyncOperation.DELETE, 0);
        when(syncService.findFailedForManualRetry(null)).thenReturn(List.of(del));
        when(syncService.claimForRetry(del, true)).thenReturn(del);
        when(syncService.find("d44-doc-x", SyncOperation.DELETE, 0))
                .thenReturn(converged(SyncOperation.DELETE, 0));

        List<SyncRetryResultView> results = runner.retryManual(null);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isPipelineCompleted());
        assertEquals(SyncStatus.SYNCED.name(), results.get(0).getSyncStatus());
        verify(indexService).deleteByDocument("d44-doc-x");
        verify(indexService, never()).retryCleanupVersion(anyString(), any());
    }

    @Test
    @DisplayName("UPDATE_CLEANUP 携带目标版本重跑清理")
    void routesUpdateCleanup() {
        DocumentSyncPO cleanup = failed(SyncOperation.UPDATE_CLEANUP, 1);
        when(syncService.findDueForRetry()).thenReturn(List.of(cleanup));
        when(syncService.claimForRetry(cleanup, false)).thenReturn(cleanup);
        when(syncService.find("d44-doc-x", SyncOperation.UPDATE_CLEANUP, 1))
                .thenReturn(converged(SyncOperation.UPDATE_CLEANUP, 1));

        List<SyncRetryResultView> results = runner.retryDue();
        assertEquals(1, results.size());
        assertTrue(results.get(0).isPipelineCompleted());
        verify(indexService).retryCleanupVersion(eq("d44-doc-x"), eq(1));
    }

    @Test
    @DisplayName("未知 operation → fail-closed 拒绝执行（绝不误调管线）")
    void unknownOperationFailClosed() {
        DocumentSyncPO bogus = failed(SyncOperation.DELETE, 0);
        bogus.setOperation("DROP_TABLE");
        when(syncService.findFailedForManualRetry("d44-doc-x")).thenReturn(List.of(bogus));
        when(syncService.claimForRetry(bogus, true)).thenReturn(bogus);
        when(syncService.find(anyString(), any(), any())).thenReturn(bogus);

        List<SyncRetryResultView> results = runner.retryManual("d44-doc-x");
        assertEquals(1, results.size());
        assertFalse(results.get(0).isPipelineCompleted());
        verify(indexService, never()).deleteByDocument(anyString());
        verify(indexService, never()).retryCleanupVersion(anyString(), any());
    }

    @Test
    @DisplayName("管线再失败（异常上抛）→ 编排层捕获不外溢，结果视图如实反映 FAILED")
    void pipelineFailureDoesNotEscape() {
        DocumentSyncPO del = failed(SyncOperation.DELETE, 0);
        when(syncService.findFailedForManualRetry(null)).thenReturn(List.of(del));
        when(syncService.claimForRetry(del, true)).thenReturn(del);
        doThrow(new RuntimeException("SIMULATED_PG_CONNECTION_LOST"))
                .when(indexService).deleteByDocument("d44-doc-x");
        when(syncService.find("d44-doc-x", SyncOperation.DELETE, 0)).thenReturn(del);

        List<SyncRetryResultView> results = runner.retryManual(null);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isPipelineCompleted());
        assertEquals(SyncStatus.FAILED.name(), results.get(0).getSyncStatus());
    }

    @Test
    @DisplayName("claim 返回 null（并发下已被他线程认领）→ 跳过不执行")
    void skipWhenClaimLost() {
        DocumentSyncPO del = failed(SyncOperation.DELETE, 0);
        when(syncService.findFailedForManualRetry(null)).thenReturn(List.of(del));
        when(syncService.claimForRetry(del, true)).thenReturn(null);

        assertTrue(runner.retryManual(null).isEmpty());
        verify(indexService, never()).deleteByDocument(anyString());
    }
}
