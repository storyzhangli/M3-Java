package com.zl.d40.document.sync;

import com.zl.d40.document.sync.dto.SyncRetryResultView;
import com.zl.d40.document.sync.mapper.DocumentSyncMapper;
import com.zl.d40.document.sync.model.SyncOperation;
import com.zl.d40.document.sync.model.SyncReasonCode;
import com.zl.d40.document.sync.model.SyncStatus;
import com.zl.d40.document.sync.po.DocumentSyncPO;
import com.zl.d40.document.sync.service.DocumentSyncService;
import com.zl.d40.document.sync.service.SyncRetryRunner;
import com.zl.d40.document.vectorindex.config.VectorIndexProperties;
import com.zl.d40.document.vectorindex.service.DocumentVectorIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R02 整改单测：FAILED 任务原子领取的语义与路由（Mockito 纯单测——
 * 原子性本体由 DB 条件 UPDATE 承载，真实 MySQL 双线程竞争验证见
 * docs\evidence\D40\repair\D40-R02-atomic-claim-realdb.json，验证边界在此明确）。
 *
 * <p>本层证明：①affectedRows=1/0 → 领取成功/null 的判定映射；②双线程竞争经
 * 线程安全 stub（CAS 模拟数据库行锁语义）恰好一方成功；③领取失败的实例
 * 绝不进入重试管线（向量删除/重建零调用——任务书测试 E）。</p>
 */
class DocumentSyncServiceAtomicClaimTest {

    private static DocumentSyncPO failedRow(long id, int attempt, SyncReasonCode reason) {
        DocumentSyncPO po = new DocumentSyncPO();
        po.setId(id);
        po.setDocumentId("d40-doc-r02");
        po.setOperation(SyncOperation.DELETE.name());
        po.setVersion(SyncOperation.ALL_VERSIONS);
        po.setSyncStatus(SyncStatus.FAILED.name());
        po.setReasonCode(reason == null ? "" : reason.name());
        po.setAttemptCount(attempt);
        return po;
    }

    private static VectorIndexProperties props() {
        VectorIndexProperties p = new VectorIndexProperties();
        p.setMaxAttempts(3);
        p.setRetryBackoffMs(500);
        return p;
    }

    @Test
    @DisplayName("A：两线程竞争同一 FAILED 行——CAS stub 模拟行锁，恰好一方领取成功且 attempt 不重复递增")
    void twoThreadsRaceYieldsExactlyOneWinner() throws Exception {
        DocumentSyncMapper mapper = mock(DocumentSyncMapper.class);
        DocumentSyncService service = new DocumentSyncService(mapper, props());
        // 模拟 DB 条件 UPDATE 的行互斥：第一次抢到置 0（CAS 成功=affected 1），其余返回 0
        AtomicInteger remaining = new AtomicInteger(1);
        when(mapper.claimFailedAtomically(eq(7L), anyInt(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> remaining.getAndSet(0) == 1 ? 1 : 0);

        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    // 每线程持独立行副本（等同两实例各自 selectDueForRetry 拿到同一行）
                    DocumentSyncPO mine = failedRow(7L, 2, SyncReasonCode.PG_UNREACHABLE);
                    try {
                        gate.await();
                        if (service.claimForRetry(mine, false) != null) {
                            wins.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            gate.countDown();
            pool.shutdown();
            assertEquals(true, pool.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, wins.get(), "两个竞争者必须恰好一个领取成功");
        // 领取不递增 attempt（递增点冻结在 markFailed）——竞争失败方也无副作用
        verify(mapper, org.mockito.Mockito.times(2))
                .claimFailedAtomically(eq(7L), eq(2), eq(""), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("B：affected=0（已被其他实例领取/状态已变化）→ 返回 null，不伪造成功视图")
    void claimLostReturnsNull() {
        DocumentSyncMapper mapper = mock(DocumentSyncMapper.class);
        DocumentSyncService service = new DocumentSyncService(mapper, props());
        when(mapper.claimFailedAtomically(eq(8L), anyInt(), anyString(), any(LocalDateTime.class)))
                .thenReturn(0);
        assertNull(service.claimForRetry(failedRow(8L, 1, SyncReasonCode.PG_DELETE_FAILED), true));
    }

    @Test
    @DisplayName("C：非 FAILED 终态/中间态（PENDING/SYNCED）与不存在行一律不发放行（本表无 INDEXING/DELETED 态，按 KP4 三态实态覆盖）")
    void nonFailedStatesNeverClaimable() {
        DocumentSyncMapper mapper = mock(DocumentSyncMapper.class);
        DocumentSyncService service = new DocumentSyncService(mapper, props());
        DocumentSyncPO pending = failedRow(9L, 0, null);
        pending.setSyncStatus(SyncStatus.PENDING.name());
        DocumentSyncPO synced = failedRow(10L, 1, null);
        synced.setSyncStatus(SyncStatus.SYNCED.name());
        assertNull(service.claimForRetry(pending, false));
        assertNull(service.claimForRetry(synced, true));
        // 即便调用方误传非 FAILED 状态（内存视图过期），Mapper WHERE 守卫仍是第二道闸：
        // 此处断言 service 层已拦截，mapper 完全不被触达
        verify(mapper, never()).claimFailedAtomically(any(), anyInt(), anyString(), any());
    }

    @Test
    @DisplayName("D：自动重试的 maxAttempts 上限在扫描 SQL 水位（selectDueForRetry 携 attempt<max），runner 无旁路")
    void autoRetryRespectsAttemptCeiling() {
        DocumentSyncMapper mapper = mock(DocumentSyncMapper.class);
        VectorIndexProperties p = props();
        p.setMaxAttempts(3);
        DocumentSyncService service = new DocumentSyncService(mapper, p);
        service.findDueForRetry();
        // 水位参数正确传递（attempt<3 语义由该 SQL 承载；真实到期+耗尽行不出现的
        // DB 级实证见 realdb harness test D 段）
        verify(mapper).selectDueForRetry(eq(3), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("E：领取失败（null）的实例不执行任何管线动作（deleteByDocument/retryCleanupVersion 零调用）")
    void losingInstanceExecutesNothing() {
        DocumentSyncService syncService = mock(DocumentSyncService.class);
        DocumentVectorIndexService indexService = mock(DocumentVectorIndexService.class);
        SyncRetryRunner runner = new SyncRetryRunner(syncService, indexService);
        DocumentSyncPO due = failedRow(11L, 1, SyncReasonCode.PG_UNREACHABLE);
        when(syncService.findDueForRetry()).thenReturn(List.of(due));
        when(syncService.claimForRetry(due, false)).thenReturn(null); // 竞争失败

        List<SyncRetryResultView> results = runner.retryDue();

        assertEquals(0, results.size());
        verify(indexService, never()).deleteByDocument(anyString());
        verify(indexService, never()).retryCleanupVersion(anyString(), any());
    }

    @Test
    @DisplayName("领取成功路径回显完整（PENDING 视图+DB 一致字段）——兼容 SyncRetryRunner 既有消费")
    void winningClaimReturnsConsistentView() {
        DocumentSyncMapper mapper = mock(DocumentSyncMapper.class);
        DocumentSyncService service = new DocumentSyncService(mapper, props());
        when(mapper.claimFailedAtomically(eq(12L), anyInt(), anyString(), any(LocalDateTime.class)))
                .thenReturn(1);
        DocumentSyncPO claimed = service.claimForRetry(
                failedRow(12L, 2, SyncReasonCode.PG_UNREACHABLE), false);
        assertNotNull(claimed);
        assertEquals(SyncStatus.PENDING.name(), claimed.getSyncStatus());
        assertEquals("", claimed.getReasonCode());
        assertNull(claimed.getNextRetryAt());
        assertEquals(2, claimed.getAttemptCount());
    }
}
