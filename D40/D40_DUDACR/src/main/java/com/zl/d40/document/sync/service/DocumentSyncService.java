package com.zl.d40.document.sync.service;

import com.zl.d40.document.sync.mapper.DocumentSyncMapper;
import com.zl.d40.document.sync.model.SyncOperation;
import com.zl.d40.document.sync.model.SyncReasonCode;
import com.zl.d40.document.sync.model.SyncStatus;
import com.zl.d40.document.sync.po.DocumentSyncPO;
import com.zl.d40.document.vectorindex.config.VectorIndexProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 操作级同步状态机服务（P1 核心；KP4 状态迁移冻结的唯一实现）。
 *
 * <p>职责边界：只读写 {@code d40_document_sync} 与提供迁移语义，<b>不执行管线</b>——
 * 管线重试编排见 {@link SyncRetryRunner}，避免与 DocumentVectorIndexService 形成
 * 依赖环（indexService 依赖本服务落状态，本服务不依赖 indexService）。</p>
 *
 * <p>迁移（sync-params.json stateMachineFrozen）：</p>
 * <ul>
 *   <li>PENDING → SYNCED：双端确认通过后由管线调用 {@link #markSynced}；</li>
 *   <li>PENDING → FAILED：{@link #markFailed} attempt+1 + 退避 + 原因码；
 *       attempt 达 maxAttempts 时原因码改写 RETRY_EXHAUSTED；</li>
 *   <li>FAILED → PENDING：{@link #claimForRetry}（手动恒可用；自动调度先过滤到期未耗尽）；
 *       耗尽行手动 claim 时 attempt 重置 0（KP4 裁决 2）。</li>
 * </ul>
 *
 * <p>事务：markPending 的"lifecycle 标记 + PENDING"原子性由调用方
 * {@code VectorIndexStateTx.markDeletedTx} 的同一 MySQL 事务承载，本服务不另开事务。</p>
 */
@Slf4j
@Service
public class DocumentSyncService {

    private final DocumentSyncMapper syncMapper;

    private final VectorIndexProperties properties;

    public DocumentSyncService(DocumentSyncMapper syncMapper, VectorIndexProperties properties) {
        this.syncMapper = syncMapper;
        this.properties = properties;
    }

    /**
     * 标记操作进入 PENDING（幂等 upsert：uk_doc_op_version 重复事件覆盖而非新增行）。
     *
     * <p>attempt_count 语义（KP4 冻结状态机）：<b>保留行内既有值</b>——首见事件行缺席=0；
     * UPDATE_CLEANUP 重试重入本方法时不清零，否则自动调度 attempt 永达不到 max、
     * RETRY_EXHAUSTED 不可达（与 DELETE 闭环 pendingRow 保留口径对齐）。
     * 唯一归零入口=手动 claim 耗尽行（KP4 裁决 2）。</p>
     *
     * @param payloadHash 操作快照指纹（排序 chunkId 集 SHA-256；可空=空串）
     */
    public void markPending(String documentId, SyncOperation operation, Integer version,
                            String payloadHash) {
        LocalDateTime now = LocalDateTime.now();
        DocumentSyncPO po = newRow(documentId, operation, version, payloadHash, now);
        po.setSyncStatus(SyncStatus.PENDING.name());
        po.setReasonCode(SyncReasonCode.NONE);
        po.setAttemptCount(currentAttempt(documentId, operation, version));
        po.setNextRetryAt(null);
        syncMapper.upsertOnNaturalKey(po);
        log.info("sync markPending documentId={} operation={} version={} payloadHash={}",
                documentId, operation, po.getVersion(), abbreviate(payloadHash));
    }

    /**
     * 双端确认通过后标记 SYNCED（终态；next_retry_at 清空）。
     */
    public void markSynced(String documentId, SyncOperation operation, Integer version,
                           String payloadHash) {
        LocalDateTime now = LocalDateTime.now();
        DocumentSyncPO po = newRow(documentId, operation, version, payloadHash, now);
        po.setSyncStatus(SyncStatus.SYNCED.name());
        po.setReasonCode(SyncReasonCode.NONE);
        po.setAttemptCount(currentAttempt(documentId, operation, version));
        po.setNextRetryAt(null);
        syncMapper.upsertOnNaturalKey(po);
        log.info("sync SYNCED documentId={} operation={} version={}", documentId, operation, po.getVersion());
    }

    /**
     * 任一步失败落库 FAILED：attempt+1，退避 next_retry_at；
     * attempt 达 maxAttempts 时原因码改写 RETRY_EXHAUSTED（待人工，KP4 裁决 2）。
     */
    public SyncReasonCode markFailed(String documentId, SyncOperation operation, Integer version,
                                     SyncReasonCode reason, String payloadHash) {
        int attempt = currentAttempt(documentId, operation, version) + 1;
        SyncReasonCode effective = attempt >= properties.getMaxAttempts()
                ? SyncReasonCode.RETRY_EXHAUSTED : reason;
        LocalDateTime now = LocalDateTime.now();
        DocumentSyncPO po = newRow(documentId, operation, version, payloadHash, now);
        po.setSyncStatus(SyncStatus.FAILED.name());
        po.setReasonCode(effective.name());
        po.setAttemptCount(attempt);
        po.setNextRetryAt(now.plusNanos(properties.getRetryBackoffMs() * 1_000_000L));
        syncMapper.upsertOnNaturalKey(po);
        log.warn("sync FAILED documentId={} operation={} version={} attempt={} reason={}",
                documentId, operation, po.getVersion(), attempt, effective);
        return effective;
    }

    /** 自然键读行（可 null）。 */
    public DocumentSyncPO find(String documentId, SyncOperation operation, Integer version) {
        return syncMapper.selectByNaturalKey(documentId, operation.name(),
                operation.normalizeVersion(version));
    }

    /**
     * 重试认领：FAILED → PENDING（attempt 已在行内；RETRY_EXHAUSTED 手动路径重置 attempt=0，
     * 其余保留 attempt 供 maxAttempts 继续判定）。返回认领后的行；非 FAILED 行返回 null（no-op）。
     */
    public DocumentSyncPO claimForRetry(DocumentSyncPO row, boolean manual) {
        if (row == null || !SyncStatus.FAILED.name().equals(row.getSyncStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        // 先捕获原原因码再改写（claim 后 reason 被清空，耗尽判定必须基于捕获值）
        boolean wasExhausted = SyncReasonCode.RETRY_EXHAUSTED.name().equals(row.getReasonCode());
        row.setSyncStatus(SyncStatus.PENDING.name());
        row.setReasonCode(SyncReasonCode.NONE);
        row.setNextRetryAt(null);
        row.setUpdatedAt(now);
        if (manual && wasExhausted) {
            row.setAttemptCount(0);
        }
        syncMapper.updateById(row);
        return row;
    }

    /** 自动调度扫描：FAILED ∧ 到期 ∧ attempt<max。 */
    public List<DocumentSyncPO> findDueForRetry() {
        return syncMapper.selectDueForRetry(properties.getMaxAttempts(), LocalDateTime.now());
    }

    /**
     * 手动重试扫描：FAILED 行（不受 attempt 限制——手动即人工介入，恒可用 H2），
     * 可按 documentId 过滤。
     */
    public List<DocumentSyncPO> findFailedForManualRetry(String documentId) {
        return syncMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocumentSyncPO>()
                        .eq("sync_status", SyncStatus.FAILED.name())
                        .eq(documentId != null && !documentId.isBlank(), "document_id", documentId)
                        .orderByAsc("updated_at"));
    }

    /** 状态查询面（/sync/status；documentId 空=全部行）。 */
    public List<DocumentSyncPO> listStatus(String documentId) {
        return syncMapper.selectForStatusView(
                documentId == null || documentId.isBlank() ? null : documentId);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private DocumentSyncPO newRow(String documentId, SyncOperation operation, Integer version,
                                  String payloadHash, LocalDateTime now) {
        DocumentSyncPO po = new DocumentSyncPO();
        po.setDocumentId(documentId);
        po.setOperation(operation.name());
        po.setVersion(operation.normalizeVersion(version));
        po.setPayloadHash(payloadHash == null ? "" : payloadHash);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }

    private int currentAttempt(String documentId, SyncOperation operation, Integer version) {
        DocumentSyncPO existing = find(documentId, operation, version);
        return existing == null || existing.getAttemptCount() == null
                ? 0 : existing.getAttemptCount();
    }

    private static String currentReason(DocumentSyncPO row) {
        return row.getReasonCode();
    }

    private static String abbreviate(String hash) {
        if (hash == null || hash.isBlank()) {
            return "";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
