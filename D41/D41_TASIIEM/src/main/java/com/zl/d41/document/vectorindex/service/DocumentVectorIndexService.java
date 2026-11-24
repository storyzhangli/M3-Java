package com.zl.d41.document.vectorindex.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d41.access.DocumentScope;
import com.zl.d41.document.chunk.model.Chunk;
import com.zl.d41.document.chunk.model.ChunkStrategy;
import com.zl.d41.document.chunk.service.DocumentChunkService;
import com.zl.d41.document.mapper.DocumentChunkIndexMapper;
import com.zl.d41.document.mapper.DocumentVectorIndexMapper;
import com.zl.d41.document.mapper.KnowledgeDocumentMapper;
import com.zl.d41.document.po.DocumentChunkIndexPO;
import com.zl.d41.document.po.DocumentVectorIndexPO;
import com.zl.d41.document.po.KnowledgeDocumentPO;
import com.zl.d41.document.service.DocumentPageService;
import com.zl.d41.document.support.Sha256ContentHasher;
import com.zl.d41.document.sync.po.DocumentSyncPO;
import com.zl.d41.document.vectorindex.VectorIndexException;
import com.zl.d41.document.vectorindex.config.VectorIndexProperties;
import com.zl.d41.document.vectorindex.dto.VectorIndexResultView;
import com.zl.d41.document.vectorindex.model.ChunkRowStatus;
import com.zl.d41.document.vectorindex.model.IndexStage;
import com.zl.d41.document.vectorindex.model.VectorIndexStatus;
import com.zl.d41.document.vectorindex.support.StableChunkIdGenerator;
import com.zl.d41.document.vectorindex.support.VectorIndexStateTx;
import com.zl.d41.document.vectorstore.PgVectorStoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档向量索引编排服务：核心。
 *
 * <p>在既有切分（{@link DocumentChunkService}）、Embedding（经 {@link VectorStore}
 * 内部调用）与 pgvector 持久化能力之上，补齐工程化的幂等、版本切换、删除与恢复：</p>
 * <ul>
 *   <li>稳定 Chunk ID（UUID v5）+ 框架原生 Upsert（ON CONFLICT）+ MySQL 唯一约束，
 *       重复导入与失败重试不产生重复数据；</li>
 *   <li>新版本先写后切（SWITCH），旧版本在新版本确认成功后才清理，避免“先删后失败全丢”；</li>
 *   <li>按文档删除分批执行且可重复执行；</li>
 *   <li>卡住任务依据 Outbox 快照（含 Chunk 文本）幂等恢复。</li>
 * </ul>
 *
 * <p>不使用跨库分布式事务：MySQL 与 PG 各自本地原子，跨系统一致性由
 * 状态机 + 核对补偿（{@code VectorReconciliationService}）收敛。</p>
 */
@Slf4j
@Service
public class DocumentVectorIndexService {

    private static final int MAX_ERROR_MESSAGE = 480;

    private final VectorStore vectorStore;

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorStoreProperties pgProperties;

    private final DocumentVectorIndexMapper indexMapper;

    private final DocumentChunkIndexMapper chunkMapper;

    private final StableChunkIdGenerator idGenerator;

    private final Sha256ContentHasher hasher;

    private final VectorIndexProperties properties;

    private final VectorIndexStateTx stateTx;

    /** 页表链路依赖：旧表未对齐时允许缺失，不影响直接 Chunk 入库。 */
    private final ObjectProvider<DocumentPageService> pageServiceProvider;

    private final ObjectProvider<DocumentChunkService> chunkServiceProvider;

    /** P2 追加：文档权限三列读取（knowledge_document 权威；缺失=无标签，metadata 不写权限键）。 */
    private final ObjectProvider<KnowledgeDocumentMapper> documentMapperProvider;

    /** P2 追加：操作级同步状态机（删除闭环/清理钩子的落库入口；不构成依赖环——本服务→syncService 单向）。 */
    private final com.zl.d41.document.sync.service.DocumentSyncService syncService;

    /** D41 追加：表格分支行数（删除幂等短路谓词；可选依赖，缺失=按零行原语义）。 */
    private final ObjectProvider<com.zl.d41.document.table.service.TableRecordService> tableServiceProvider;

    public DocumentVectorIndexService(VectorStore vectorStore,
                                      @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                      PgVectorStoreProperties pgProperties,
                                      DocumentVectorIndexMapper indexMapper,
                                      DocumentChunkIndexMapper chunkMapper,
                                      StableChunkIdGenerator idGenerator,
                                      Sha256ContentHasher hasher,
                                      VectorIndexProperties properties,
                                      VectorIndexStateTx stateTx,
                                      ObjectProvider<DocumentPageService> pageServiceProvider,
                                      ObjectProvider<DocumentChunkService> chunkServiceProvider,
                                      ObjectProvider<KnowledgeDocumentMapper> documentMapperProvider,
                                      com.zl.d41.document.sync.service.DocumentSyncService syncService,
                                      ObjectProvider<com.zl.d41.document.table.service.TableRecordService> tableServiceProvider) {
        this.vectorStore = vectorStore;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.pgProperties = pgProperties;
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
        this.idGenerator = idGenerator;
        this.hasher = hasher;
        this.properties = properties;
        this.stateTx = stateTx;
        this.pageServiceProvider = pageServiceProvider;
        this.chunkServiceProvider = chunkServiceProvider;
        this.documentMapperProvider = documentMapperProvider;
        this.syncService = syncService;
        this.tableServiceProvider = tableServiceProvider;
    }

    // ------------------------------------------------------------------
    // 核心：幂等入库
    // ------------------------------------------------------------------

    /**
     * 对给定 Chunk 集合执行幂等向量化入库。
     *
     * @param documentId 文档标识
     * @param version    版本，为空默认 1
     * @param strategy   策略，为空用默认策略
     * @param title      标题（可空）
     * @param chunks     Chunk 列表
     */
    public VectorIndexResultView indexChunks(String documentId, Integer version,
                                             String strategy, String title,
                                             List<Chunk> chunks) {
        return indexChunks(documentId, version, strategy, title, chunks, false);
    }

    /**
     * P4 修复追加（C12b 实测缺陷）：{@code forceReindex=true} 跳过幂等短路。
     *
     * <p>幂等短路面三元组（COMPLETED ∧ payloadHash ∧ 关联数）不含 PG 侧——对
     * <b>重复事件</b>（E4 冻结语义）成立；但一致性修复的 META_ONLY 场景恰是
     * "MySQL 三面一致而 PG 缺行"，默认调用会被短路成零写入（C12b 第一遍实测）。
     * force 仅由 {@code VectorReconciliationService} 修复路径使用；默认重载行为
     * 逐字节不变，E4 语义不受影响。</p>
     */
    public VectorIndexResultView indexChunks(String documentId, Integer version,
                                             String strategy, String title,
                                             List<Chunk> chunks, boolean forceReindex) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("待索引 Chunk 不能为空");
        }
        int targetVersion = version == null ? 1 : version;
        ChunkStrategy targetStrategy = resolveStrategy(strategy);
        List<Chunk> normalized = normalizeChunks(
                documentId, targetVersion, targetStrategy, title, chunks);

        LocalDateTime now = LocalDateTime.now();
        DocumentVectorIndexPO existing = findIndexRow(documentId, targetVersion);
        String payloadHash = hasher.hash(buildPayloadText(normalized));

        // 重复导入幂等短路：已完成且指纹与关联数一致，不再产生写入（forceReindex=修复态跳过）
        if (!forceReindex
                && existing != null
                && VectorIndexStatus.COMPLETED.name().equals(existing.getStatus())
                && payloadHash.equals(existing.getContentHash())
                && chunkMapper.selectByDocumentVersion(documentId, targetVersion).size()
                        == normalized.size()) {
            log.info("重复导入命中幂等，documentId={}, version={}", documentId, targetVersion);
            return toView(existing, normalized.size(), 0, true);
        }

        // 建立/更新任务行并进入 INDEXING
        int attempt = existing == null ? 1 : existing.getAttemptCount() + 1;
        DocumentVectorIndexPO task = existing != null ? existing : new DocumentVectorIndexPO();
        task.setDocumentId(documentId);
        task.setVersion(targetVersion);
        task.setContentHash(payloadHash);
        task.setStrategy(targetStrategy.name());
        task.setChunkCount(normalized.size());
        task.setStatus(VectorIndexStatus.INDEXING.name());
        task.setStage(IndexStage.SPLIT.name());
        task.setErrorCode("");
        task.setErrorMessage("");
        task.setAttemptCount(attempt);
        task.setNextRetryAt(null);
        if (task.getId() == null) {
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            try {
                indexMapper.upsertOnNaturalKey(task);
            } catch (org.springframework.dao.DuplicateKeyException concurrentCreate) {
                // 并发首建：唯一约束 uk_doc_version 已让其中一个事务胜出，
                // 这里改为读取胜出行并继续后续写入，不向调用方抛错，不产生重复任务。
                DocumentVectorIndexPO winner = findIndexRow(documentId, targetVersion);
                if (winner == null) {
                    throw concurrentCreate;
                }
                task = winner;
                task.setContentHash(payloadHash);
                task.setStrategy(targetStrategy.name());
                task.setChunkCount(normalized.size());
                task.setStatus(VectorIndexStatus.INDEXING.name());
                task.setStage(IndexStage.SPLIT.name());
                task.setErrorCode("");
                task.setErrorMessage("");
                task.setAttemptCount(winner.getAttemptCount() == null
                        ? 1 : winner.getAttemptCount() + 1);
                task.setNextRetryAt(null);
                persistState(task, LocalDateTime.now());
            }
        } else {
            persistState(task, now);
        }

        // P2：入库前一次性读取文档权限范围（服务端权威列，不信任请求体声明）
        DocumentScope permission = lookupDocumentScope(documentId);

        // 计算稳定 ID 与内容指纹
        List<PreparedChunk> prepared = new ArrayList<>(normalized.size());
        for (Chunk chunk : normalized) {
            String chunkHash = hasher.hash(chunk.getContent());
            String chunkId = idGenerator.generate(chunk, chunkHash, properties.getSplitVersion());
            prepared.add(new PreparedChunk(chunk, chunkHash, chunkId));
        }

        // 分批 Embedding + Upsert
        int processed = 0;
        int batchSize = Math.max(1, properties.getBatchSize());
        try {
            for (int from = 0; from < prepared.size(); from += batchSize) {
                int to = Math.min(from + batchSize, prepared.size());
                List<PreparedChunk> batch = prepared.subList(from, to);
                List<Document> documents = new ArrayList<>(batch.size());
                for (PreparedChunk pc : batch) {
                    documents.add(Document.builder()
                            .id(pc.chunkId())
                            .text(pc.chunk().getContent())
                            .metadata(buildMetadata(pc, permission))
                            .build());
                }
                // 框架原生 DB 级 Upsert：稳定 UUID 冲突即更新，不产生重复行
                vectorStore.add(documents);
                LocalDateTime rowTime = LocalDateTime.now();
                for (PreparedChunk pc : batch) {
                    chunkMapper.upsertOnNaturalKey(toChunkPO(pc, rowTime));
                }
                processed += batch.size();
                task.setStage(IndexStage.EMBED_WRITE.name());
                task.setUpdatedAt(LocalDateTime.now());
                indexMapper.updateById(task);
            }
        } catch (Exception ex) {
            handleWriteFailure(task, processed, ex);
            throw new VectorIndexException(task.getErrorCode(),
                    "向量写入失败，已持久化现场可恢复: " + safeMessage(ex), ex);
        }

        // 写入后核对：PG 数与关联数都必须等于 Chunk 数
        long pgCount = countPgForVersion(documentId, targetVersion);
        int relationCount = chunkMapper.selectByDocumentVersion(documentId, targetVersion).size();
        if (pgCount != normalized.size() || relationCount != normalized.size()) {
            task.setStatus(VectorIndexStatus.PARTIAL.name());
            task.setStage(IndexStage.EMBED_WRITE.name());
            task.setErrorCode("COUNT_MISMATCH");
            task.setErrorMessage(safeMessage(
                    "核对不一致 pg=%d relation=%d expected=%d"
                            .formatted(pgCount, relationCount, normalized.size())));
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(1));
            persistState(task, LocalDateTime.now());
            throw new VectorIndexException("COUNT_MISMATCH",
                    "写入后核对不一致，等待核对补偿", null);
        }

        // 版本切换：旧 COMPLETED 置 SUPERSEDED，当前置 COMPLETED（MySQL 本地事务）
        List<DocumentVectorIndexPO> olderCompleted = findOlderCompleted(documentId, targetVersion);
        LocalDateTime switchTime = LocalDateTime.now();
        task.setStage(IndexStage.SWITCH.name());
        indexMapper.updateById(task);
        stateTx.switchVersion(task, olderCompleted, switchTime);

        // 清理旧版本向量（失败不阻断新版本，留给核对补偿）
        cleanupSuperseded(documentId);

        task.setStatus(VectorIndexStatus.COMPLETED.name());
        task.setStage(IndexStage.CLEANUP.name());
        task.setErrorCode("");
        task.setErrorMessage("");
        task.setNextRetryAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        indexMapper.updateById(task);

        log.info("向量索引完成 documentId={} version={} chunks={} attempt={}",
                documentId, targetVersion, normalized.size(), attempt);
        return toView(task, normalized.size(), processed, false);
    }

    // ------------------------------------------------------------------
    // 页表链路：加载页 -> 切分 -> 入库
    // ------------------------------------------------------------------

    /**
     * 从文档页表加载页面，按策略切分后入库。
     *
     * <p>依赖 MySQL 原文页表；旧表未对齐时会快速失败并给出明确错误，
     * 不伪造成功。</p>
     */
    public VectorIndexResultView indexFromPages(String documentId, String strategy) {
        DocumentPageService pageService = pageServiceProvider.getIfAvailable();
        DocumentChunkService chunkService = chunkServiceProvider.getIfAvailable();
        if (pageService == null || chunkService == null) {
            throw new VectorIndexException("PAGE_PIPELINE_UNAVAILABLE",
                    "页表/切分链路未就绪，无法从原文页入库", null);
        }
        ChunkStrategy targetStrategy = resolveStrategy(strategy);
        var pages = pageService.listPages(documentId);
        if (pages == null || pages.isEmpty()) {
            throw new VectorIndexException("NO_PAGES",
                    "文档没有可用页面，documentId=" + documentId, null);
        }
        List<Chunk> chunks = chunkService.runStrategy(pages, targetStrategy);
        return indexChunks(documentId, null, targetStrategy.name(), null, chunks);
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    /**
     * 按文档删除全部版本向量与关联：分批执行，可重复执行。
     *
     * <p>PG 删除语句 {@code DELETE ... WHERE id=?} 对已不存在的 id 不报错，
     * 因此中途失败后重试仍然安全；MySQL 行在 PG 删除成功后才清理。</p>
     *
     * <p><b>P2 同步闭环</b>（任务书 D1/D2 裁决）：</p>
     * <ol>
     *   <li>幂等短路：已收敛（无关联/无任务/PG=0 ∧ sync 行 SYNCED）→ 零写入零状态变化返回
     *       （重复事件 E4 判据）；</li>
     *   <li>标记事务：lifecycle→DEPRECATED + sync PENDING 同一 MySQL 本地事务
     *       （{@code stateTx.markDeletedTx}）；</li>
     *   <li>PG 分批删除：连接类异常→PG_UNREACHABLE，其余→PG_DELETE_FAILED 落库后异常上抛
     *       （状态已落库可查询可重试）；</li>
     *   <li>MySQL 关联/任务行清理；</li>
     *   <li>双端确认（唯一 SYNCED 判据）：PG count=0 ∧ 关联行=0 ∧ 任务行=0，
     *       任一非零→CONFIRM_MISMATCH 落库上抛（§3.4：只清一端不算收敛）。</li>
     * </ol>
     */
    public void deleteByDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        List<DocumentChunkIndexPO> rows = chunkMapper.selectByDocumentId(documentId);
        List<String> ids = new ArrayList<>(rows.size());
        for (DocumentChunkIndexPO row : rows) {
            ids.add(row.getChunkId());
        }
        String payloadHash = hasher.hash(chunkIdSetText(ids));
        DocumentSyncPO existingSync = syncService.find(documentId,
                com.zl.d41.document.sync.model.SyncOperation.DELETE, null);

        // 步骤 0：重复删除事件幂等短路（不写 PG、不动 MySQL、不改 sync 状态、attempt 不增长）
        // D41 追加谓词：表格行必须同样清零才可短路——否则"从未索引、曾空删、后有表格入库"
        // 文档的表行会被短路永久遗漏（文档行仍 COMPLETED，reconcile 也检不出）。
        long taskRows = indexMapper.selectCount(new QueryWrapper<DocumentVectorIndexPO>()
                .eq("document_id", documentId));
        long pgRemaining = countPgForDocument(documentId);
        long tableRemaining = 0L;
        com.zl.d41.document.table.service.TableRecordService tableSvc =
                tableServiceProvider.getIfAvailable();
        if (tableSvc != null) {
            tableRemaining = tableSvc.countForDocument(documentId);
        }
        if (rows.isEmpty() && taskRows == 0 && pgRemaining == 0 && tableRemaining == 0
                && existingSync != null
                && com.zl.d41.document.sync.model.SyncStatus.SYNCED.name()
                        .equals(existingSync.getSyncStatus())) {
            log.info("重复删除事件命中已收敛 SYNCED，幂等短路零写入 documentId={}", documentId);
            return;
        }

        // 步骤 1：标记事务（tombstone + PENDING 原子）
        LocalDateTime markTime = LocalDateTime.now();
        DocumentSyncPO pendingRow = new DocumentSyncPO();
        pendingRow.setDocumentId(documentId);
        pendingRow.setOperation(com.zl.d41.document.sync.model.SyncOperation.DELETE.name());
        pendingRow.setVersion(com.zl.d41.document.sync.model.SyncOperation.DELETE
                .normalizeVersion(null));
        pendingRow.setSyncStatus(com.zl.d41.document.sync.model.SyncStatus.PENDING.name());
        pendingRow.setReasonCode("");
        pendingRow.setAttemptCount(existingSync == null || existingSync.getAttemptCount() == null
                ? 0 : existingSync.getAttemptCount());
        pendingRow.setNextRetryAt(null);
        pendingRow.setPayloadHash(payloadHash);
        pendingRow.setCreatedAt(markTime);
        pendingRow.setUpdatedAt(markTime);
        try {
            stateTx.markDeletedTx(documentId, pendingRow, markTime);
        } catch (Exception markEx) {
            syncService.markFailed(documentId,
                    com.zl.d41.document.sync.model.SyncOperation.DELETE, null,
                    com.zl.d41.document.sync.model.SyncReasonCode.MARK_FAILED, payloadHash);
            throw new VectorIndexException("MARK_FAILED",
                    "删除标记事务失败，状态已落库可重试: " + safeMessage(markEx), markEx);
        }

        int batchSize = Math.max(1, properties.getBatchSize());
        // 步骤 2：PG 分批删除（失败落库后上抛——重试安全）
        try {
            for (int from = 0; from < ids.size(); from += batchSize) {
                int to = Math.min(from + batchSize, ids.size());
                vectorStore.delete(ids.subList(from, to));
            }
        } catch (Exception pgEx) {
            com.zl.d41.document.sync.model.SyncReasonCode reason =
                    com.zl.d41.document.sync.model.SyncReasonCode.classify(pgEx);
            syncService.markFailed(documentId,
                    com.zl.d41.document.sync.model.SyncOperation.DELETE, null, reason, payloadHash);
            throw new VectorIndexException(reason.name(),
                    "PG 向量删除失败，状态已落库可重试: " + safeMessage(pgEx), pgEx);
        }

        // 步骤 3：MySQL 关联/任务行清理；失败不直接抛，进入双端确认按事实归因 CONFIRM_MISMATCH
        try {
            stateTx.deleteDocumentRows(documentId);
        } catch (Exception rowsEx) {
            log.warn("MySQL 关联清理异常，进入双端确认按事实裁决 documentId={} type={}",
                    documentId, rowsEx.getClass().getSimpleName());
        }

        // 步骤 4：双端确认（SYNCED 唯一判据）
        long pgAfter = countPgForDocument(documentId);
        long chunkAfter = chunkMapper.selectCount(new QueryWrapper<DocumentChunkIndexPO>()
                .eq("document_id", documentId));
        long taskAfter = indexMapper.selectCount(new QueryWrapper<DocumentVectorIndexPO>()
                .eq("document_id", documentId));
        if (pgAfter != 0 || chunkAfter != 0 || taskAfter != 0) {
            syncService.markFailed(documentId,
                    com.zl.d41.document.sync.model.SyncOperation.DELETE, null,
                    com.zl.d41.document.sync.model.SyncReasonCode.CONFIRM_MISMATCH, payloadHash);
            throw new VectorIndexException("CONFIRM_MISMATCH",
                    "双端确认不一致 pg=%d chunk=%d task=%d，状态已落库可重试"
                            .formatted(pgAfter, chunkAfter, taskAfter), null);
        }
        syncService.markSynced(documentId,
                com.zl.d41.document.sync.model.SyncOperation.DELETE, null, payloadHash);
        log.info("文档向量已删除并双端确认 documentId={} vectors={} confirm=0/0/0",
                documentId, ids.size());
    }

    /**
     * P2：UPDATE_CLEANUP 单版本重试入口（{@code SyncRetryRunner} 管线回调，H4）。
     *
     * <p>语义=对 SUPERSEDED 旧版本重跑清理：PG 分批删除 → MySQL 该版本关联行清理 →
     * 版本级双端确认 → SYNCED/FAILED 落库。关联行缺失但 PG 向量在（Outbox 无快照）→
     * 转孤儿补偿：按 PG 实查 id 走 {@link #deleteVectorsByIds} 补偿管线（SNAPSHOT_MISSING
     * 落原因码后再清，不裸 SQL 直改）。</p>
     */
    public void retryCleanupVersion(String documentId, Integer version) {
        com.zl.d41.document.sync.model.SyncOperation operation =
                com.zl.d41.document.sync.model.SyncOperation.UPDATE_CLEANUP;
        if (documentId == null || documentId.isBlank() || version == null || version <= 0) {
            throw new IllegalArgumentException("retryCleanupVersion 需 documentId 与正版本号");
        }
        List<DocumentChunkIndexPO> oldRows =
                chunkMapper.selectByDocumentVersion(documentId, version);
        List<String> ids = new ArrayList<>(oldRows.size());
        for (DocumentChunkIndexPO r : oldRows) {
            ids.add(r.getChunkId());
        }
        String payloadHash = hasher.hash(chunkIdSetText(ids));
        syncService.markPending(documentId, operation, version, payloadHash);

        int batchSize = Math.max(1, properties.getBatchSize());
        try {
            if (ids.isEmpty()) {
                // 快照缺失态：PG 仍在（版本级实查 id）→ 补偿管线删除；PG 也空 → 直接进确认
                List<String> pgIds = listPgIdsForVersion(documentId, version);
                if (!pgIds.isEmpty()) {
                    syncService.markFailed(documentId, operation, version,
                            com.zl.d41.document.sync.model.SyncReasonCode.SNAPSHOT_MISSING,
                            payloadHash);
                    log.warn("清理重试遇关联缺但 PG 向量在，转孤儿补偿 documentId={} version={} pgIds={}",
                            documentId, version, pgIds.size());
                    for (int from = 0; from < pgIds.size(); from += batchSize) {
                        int to = Math.min(from + batchSize, pgIds.size());
                        deleteVectorsByIds(pgIds.subList(from, to));
                    }
                }
            } else {
                for (int from = 0; from < ids.size(); from += batchSize) {
                    int to = Math.min(from + batchSize, ids.size());
                    vectorStore.delete(ids.subList(from, to));
                }
            }
        } catch (Exception cleanupEx) {
            com.zl.d41.document.sync.model.SyncReasonCode reason =
                    com.zl.d41.document.sync.model.SyncReasonCode.classify(cleanupEx);
            syncService.markFailed(documentId, operation, version, reason, payloadHash);
            throw new VectorIndexException(reason.name(),
                    "旧版本清理重试失败，状态已落库可继续重试: " + safeMessage(cleanupEx), cleanupEx);
        }

        // 版本级 MySQL 关联清理（异常进入确认按事实归因，同删除闭环裁决）
        try {
            stateTx.deleteOldVersionRows(documentId, version);
        } catch (Exception rowsEx) {
            log.warn("旧版本关联清理异常，进入版本级双端确认 documentId={} version={} type={}",
                    documentId, version, rowsEx.getClass().getSimpleName());
        }

        long pgAfter = countPgForVersion(documentId, version);
        long chunkAfter = chunkMapper.selectCount(new QueryWrapper<DocumentChunkIndexPO>()
                .eq("document_id", documentId).eq("version", version));
        if (pgAfter != 0 || chunkAfter != 0) {
            syncService.markFailed(documentId, operation, version,
                    com.zl.d41.document.sync.model.SyncReasonCode.CONFIRM_MISMATCH, payloadHash);
            throw new VectorIndexException("CONFIRM_MISMATCH",
                    "旧版本双端确认不一致 pg=%d chunk=%d".formatted(pgAfter, chunkAfter), null);
        }
        syncService.markSynced(documentId, operation, version, payloadHash);
        log.info("旧版本清理重试收敛 SYNCED documentId={} version={}", documentId, version);
    }

    // ------------------------------------------------------------------
    // 卡住恢复
    // ------------------------------------------------------------------

    /**
     * 恢复卡住任务（全量语义，兼容既有调用方）：扫描 PENDING/INDEXING 且超过 stuckMinutes 的行，
     * 依据 Outbox 中的 Chunk 快照重新幂等入库。
     *
     * <p>R01 整改：一致性补偿路径必须走 {@link #recoverStuckTasks(String)} 传递 scope，
     * 本无参入口仅保留给管理端点显式全量恢复场景。</p>
     *
     * @return 实际恢复的任务结果
     */
    public List<VectorIndexResultView> recoverStuckTasks() {
        return recoverStuckTasks(null);
    }

    /**
     * 恢复卡住任务（R01 范围隔离）：documentPrefix 非空时仅恢复
     * {@code document_id LIKE 'prefix%'} 的任务——过滤在 Mapper SQL 层完成
     * （likeRight=前缀 LIKE，走索引可用形态），执行前逐行二次校验，
     * 防止查询与执行之间混入范围外行；范围外任务的向量、关联、attempt 一律不触碰。
     *
     * @param documentPrefix null/空白=全量恢复（保留原语义）
     */
    public List<VectorIndexResultView> recoverStuckTasks(String documentPrefix) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getStuckMinutes());
        QueryWrapper<DocumentVectorIndexPO> query =
                new QueryWrapper<DocumentVectorIndexPO>()
                        .in("status",
                                VectorIndexStatus.PENDING.name(),
                                VectorIndexStatus.INDEXING.name())
                        .lt("updated_at", cutoff);
        boolean scoped = documentPrefix != null && !documentPrefix.isBlank();
        if (scoped) {
            query.likeRight("document_id", documentPrefix);
        }
        List<DocumentVectorIndexPO> stuck = indexMapper.selectList(query);
        List<VectorIndexResultView> results = new ArrayList<>();
        for (DocumentVectorIndexPO task : stuck) {
            // 执行前范围终校验（查询→执行防线：行在并发窗口被改动的可能性由幂等管线兜底，
            // 但范围外行绝不允许进入本路径恢复）
            if (scoped && (task.getDocumentId() == null
                    || !task.getDocumentId().startsWith(documentPrefix))) {
                log.warn("范围外卡住行被跳过（防御性拦截） documentId={} prefix={}",
                        task.getDocumentId(), documentPrefix);
                continue;
            }
            if (task.getAttemptCount() != null && task.getAttemptCount() >= properties.getMaxAttempts()) {
                log.warn("卡住任务已达最大尝试次数，跳过自动恢复，需人工介入 documentId={} version={}",
                        task.getDocumentId(), task.getVersion());
                continue;
            }
            List<DocumentChunkIndexPO> snapshot =
                    chunkMapper.selectByDocumentVersion(task.getDocumentId(), task.getVersion());
            if (snapshot.isEmpty()) {
                // 无快照意味着在写入前崩溃，无法重建载荷，标记最终失败等待人工处理
                task.setStatus(VectorIndexStatus.FAILED.name());
                task.setErrorCode("NO_SNAPSHOT");
                task.setErrorMessage("写入前崩溃且无 Outbox 快照，需重新触发索引");
                task.setUpdatedAt(LocalDateTime.now());
                indexMapper.updateById(task);
                continue;
            }
            List<Chunk> chunks = new ArrayList<>(snapshot.size());
            for (DocumentChunkIndexPO po : snapshot) {
                chunks.add(toChunk(po));
            }
            results.add(indexChunks(task.getDocumentId(), task.getVersion(),
                    task.getStrategy(), null, chunks));
        }
        return results;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<DocumentVectorIndexPO> getStatus(String documentId) {
        return indexMapper.selectList(
                new QueryWrapper<DocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .orderByAsc("version"));
    }

    public List<DocumentChunkIndexPO> listChunks(String documentId) {
        return chunkMapper.selectByDocumentId(documentId);
    }

    public VectorIndexProperties getProperties() {
        return properties;
    }

    /**
     * 按稳定 ID 直接删除 PG 向量（补偿删除入口）。
     *
     * <p>仅由核对补偿调用；PG 对已不存在的 id 不报错，重复执行安全。
     * 不自动清理 MySQL 关联行，调用方按差异类型决定是否联动。</p>
     */
    public void deleteVectorsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
    }

    /**
     * PG 中指定文档版本的向量数（按 metadata 过滤）。
     */
    public long countPgForVersion(String documentId, Integer version) {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable()
                + " WHERE (metadata->>'documentId') = ? AND (metadata->>'version') = ?";
        Long count = pgVectorJdbcTemplate.queryForObject(sql, Long.class,
                documentId, String.valueOf(version));
        return count == null ? 0L : count;
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    /**
     * 旧版本清理（SWITCH 后调用；不阻断新版本收敛）。
     *
     * <p><b>P2 钩子</b>（D4 裁决：失败必落库）：单版本清理异常不再是仅 warn——
     * 建 UPDATE_CLEANUP sync FAILED 任务 + 细分原因码（异常分类冻结口径），
     * 由 sync 手动/自动重试管线收敛；成功则记 SYNCED 行（操作级事实留痕，
     * 同时支撑 DRIFT_SYNC_STUCK/复查断言）。保持非阻断语义：新版本已活动，不上抛。</p>
     */
    private void cleanupSuperseded(String documentId) {
        List<DocumentVectorIndexPO> superseded = indexMapper.selectList(
                new QueryWrapper<DocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .eq("status", VectorIndexStatus.SUPERSEDED.name()));
        for (DocumentVectorIndexPO old : superseded) {
            List<String> oldIds = new ArrayList<>();
            String payloadHash = "";
            try {
                List<DocumentChunkIndexPO> oldRows =
                        chunkMapper.selectByDocumentVersion(documentId, old.getVersion());
                for (DocumentChunkIndexPO r : oldRows) {
                    oldIds.add(r.getChunkId());
                }
                payloadHash = hasher.hash(chunkIdSetText(oldIds));
                int batchSize = Math.max(1, properties.getBatchSize());
                for (int from = 0; from < oldIds.size(); from += batchSize) {
                    int to = Math.min(from + batchSize, oldIds.size());
                    vectorStore.delete(oldIds.subList(from, to));
                }
                stateTx.deleteOldVersionRows(documentId, old.getVersion());
                syncService.markSynced(documentId,
                        com.zl.d41.document.sync.model.SyncOperation.UPDATE_CLEANUP,
                        old.getVersion(), payloadHash);
            } catch (Exception cleanupEx) {
                // 非致命语义保留（不上抛），但失败必须落库——UPDATE_CLEANUP FAILED + 原因码
                com.zl.d41.document.sync.model.SyncReasonCode reason =
                        com.zl.d41.document.sync.model.SyncReasonCode.classify(cleanupEx);
                syncService.markFailed(documentId,
                        com.zl.d41.document.sync.model.SyncOperation.UPDATE_CLEANUP,
                        old.getVersion(), reason, payloadHash);
                log.warn("旧版本清理失败已落库 UPDATE_CLEANUP FAILED，等待 sync 重试收敛 documentId={} version={} reason={} type={}",
                        documentId, old.getVersion(), reason, cleanupEx.getClass().getSimpleName());
            }
        }
    }

    /** 幂等证明引用：受影响 chunkId 集排序连接文本（KP4 四元组之一，禁口头断言）。 */
    private static String chunkIdSetText(List<String> ids) {
        List<String> sorted = new ArrayList<>(ids);
        java.util.Collections.sort(sorted);
        return String.join("\n", sorted);
    }

    /** PG 中文档全版本向量数（DELETE 双端确认引用）。 */
    public long countPgForDocument(String documentId) {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable()
                + " WHERE (metadata->>'documentId') = ?";
        Long count = pgVectorJdbcTemplate.queryForObject(sql, Long.class, documentId);
        return count == null ? 0L : count;
    }

    /** PG 中文档指定版本的向量 id 实查（快照缺失转孤儿补偿引用，H4 管线口径）。 */
    public List<String> listPgIdsForVersion(String documentId, Integer version) {
        String sql = "SELECT id::text FROM " + qualifiedTable()
                + " WHERE (metadata->>'documentId') = ? AND (metadata->>'version') = ?";
        return pgVectorJdbcTemplate.queryForList(sql, String.class,
                documentId, String.valueOf(version));
    }

    private void handleWriteFailure(DocumentVectorIndexPO task, int processed, Exception ex) {
        task.setStatus(processed > 0
                ? VectorIndexStatus.PARTIAL.name()
                : VectorIndexStatus.FAILED.name());
        task.setStage(IndexStage.EMBED_WRITE.name());
        task.setErrorCode("EMBED_WRITE_FAILED");
        task.setErrorMessage(safeMessage(ex));
        task.setNextRetryAt(LocalDateTime.now()
                .plusNanos(properties.getRetryBackoffMs() * 1_000_000L));
        persistState(task, LocalDateTime.now());
        log.warn("向量写入中断 documentId={} version={} processed={} reason={}",
                task.getDocumentId(), task.getVersion(), processed, safeMessage(ex));
    }

    private void persistState(DocumentVectorIndexPO task, LocalDateTime now) {
        indexMapper.updateState(task.getDocumentId(), task.getVersion(),
                task.getStatus(), task.getStage(), task.getErrorCode(), task.getErrorMessage(),
                task.getAttemptCount(), task.getNextRetryAt(), task.getChunkCount(), now);
    }

    /**
     * P2：读取文档权限三列（knowledge_document 权威）。
     * 文档行不存在 / mapper 缺席 / 三列全空 → 返回 null（无标签，metadata 不写权限键）。
     * 查询异常不吞——入库 fail-closed（权限链路不允许静默降级为无标签，H5 同向）。
     */
    private DocumentScope lookupDocumentScope(String documentId) {
        KnowledgeDocumentMapper mapper = documentMapperProvider.getIfAvailable();
        if (mapper == null) {
            return null;
        }
        KnowledgeDocumentPO po = mapper.selectOne(
                new QueryWrapper<KnowledgeDocumentPO>().eq("document_id", documentId));
        if (po == null) {
            return null;
        }
        return new DocumentScope(documentId, po.getTenantId(), po.getDeptId(), po.getDeviceId());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private DocumentVectorIndexPO findIndexRow(String documentId, Integer version) {
        List<DocumentVectorIndexPO> rows = indexMapper.selectList(
                new QueryWrapper<DocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .eq("version", version));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<DocumentVectorIndexPO> findOlderCompleted(String documentId, Integer version) {
        return indexMapper.selectList(
                new QueryWrapper<DocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .ne("version", version)
                        .eq("status", VectorIndexStatus.COMPLETED.name()));
    }

    private Map<String, Object> buildMetadata(PreparedChunk pc, DocumentScope permission) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Chunk chunk = pc.chunk();
        metadata.put("documentId", chunk.getDocumentId());
        if (chunk.getTitle() != null) {
            metadata.put("title", chunk.getTitle());
        }
        metadata.put("version", chunk.getVersion());
        metadata.put("strategy", chunk.getStrategy().name());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("chunkContentHash", pc.chunkContentHash());
        metadata.put("splitVersion", properties.getSplitVersion());

        // P2（D2 裁决）：权限三键有值才写（含显式 '*' 通配）；无标签文档不写键——
        // json 缺失键在 eq/in 谓词下 NULL 不成立，天然被权限端点排除（D3 fail-closed，KP3 实证）。
        if (permission != null) {
            if (isNotBlank(permission.tenantId())) {
                metadata.put("tenantId", permission.tenantId());
            }
            if (isNotBlank(permission.deptId())) {
                metadata.put("deptId", permission.deptId());
            }
            if (isNotBlank(permission.deviceId())) {
                metadata.put("deviceId", permission.deviceId());
            }
        }

        // 真实页码/段落定位：仅写入切分阶段保留的真实非空值，缺失则不写，禁止推断或补造。
        if (chunk.hasPageLocation()) {
            metadata.put("page", chunk.getFirstPageNumber());
            if (chunk.isCrossPage() && !chunk.getFirstPageNumber().equals(chunk.getLastPageNumber())) {
                metadata.put("lastPage", chunk.getLastPageNumber());
            }
        }
        if (chunk.hasParagraphLocation() && !chunk.getParagraphIndices().isEmpty()) {
            metadata.put("paragraphIndex", chunk.getParagraphIndices().get(0));
            if (chunk.getParagraphIndices().size() > 1) {
                metadata.put("paragraphIndices", chunk.getParagraphIndices());
            }
        }
        return metadata;
    }

    private DocumentChunkIndexPO toChunkPO(PreparedChunk pc, LocalDateTime time) {
        DocumentChunkIndexPO po = new DocumentChunkIndexPO();
        Chunk chunk = pc.chunk();
        po.setDocumentId(chunk.getDocumentId());
        po.setVersion(chunk.getVersion());
        po.setStrategy(chunk.getStrategy().name());
        po.setChunkId(pc.chunkId());
        po.setChunkIndex(chunk.getChunkIndex());
        po.setContent(chunk.getContent());
        po.setChunkContentHash(pc.chunkContentHash());
        po.setCharLength(chunk.getLength());
        po.setGlobalStart(chunk.getGlobalStart());
        po.setGlobalEnd(chunk.getGlobalEnd());
        po.setStatus(ChunkRowStatus.INDEXED.name());
        po.setCreatedAt(time);
        po.setUpdatedAt(time);
        return po;
    }

    private List<Chunk> normalizeChunks(String documentId, int version,
                                        ChunkStrategy strategy, String title,
                                        List<Chunk> source) {
        List<Chunk> result = new ArrayList<>(source.size());
        for (Chunk original : source) {
            if (original == null || original.getContent() == null
                    || original.getContent().isBlank()) {
                throw new IllegalArgumentException("存在空白 Chunk，禁止写入无意义向量");
            }
            Chunk chunk = new Chunk();
            chunk.setDocumentId(documentId);
            chunk.setTitle(title != null ? title : original.getTitle());
            chunk.setVersion(version);
            chunk.setStrategy(strategy);
            chunk.setChunkIndex(original.getChunkIndex());
            chunk.setContent(original.getContent());
            chunk.setLength(original.getContent().length());
            chunk.setCodePointCount(original.getContent().codePointCount(
                    0, original.getContent().length()));
            chunk.setGlobalStart(original.getGlobalStart());
            chunk.setGlobalEnd(original.getGlobalEnd());
            chunk.setSources(original.getSources());
            result.add(chunk);
        }
        result.sort(java.util.Comparator.comparingInt(Chunk::getChunkIndex));
        return result;
    }

    private Chunk toChunk(DocumentChunkIndexPO po) {
        Chunk chunk = new Chunk();
        chunk.setDocumentId(po.getDocumentId());
        chunk.setVersion(po.getVersion());
        chunk.setStrategy(ChunkStrategy.valueOf(po.getStrategy()));
        chunk.setChunkIndex(po.getChunkIndex());
        chunk.setContent(po.getContent());
        chunk.setLength(po.getCharLength());
        chunk.setGlobalStart(po.getGlobalStart());
        chunk.setGlobalEnd(po.getGlobalEnd());
        return chunk;
    }

    private ChunkStrategy resolveStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return ChunkStrategy.valueOf(properties.getDefaultStrategy());
        }
        return ChunkStrategy.valueOf(strategy.trim());
    }

    private String buildPayloadText(List<Chunk> chunks) {
        StringBuilder builder = new StringBuilder();
        for (Chunk chunk : chunks) {
            builder.append(chunk.getChunkIndex()).append(':').append(chunk.getContent()).append('\n');
        }
        return builder.toString();
    }

    private String qualifiedTable() {
        return "\"" + pgProperties.getSchema() + "\".\"" + pgProperties.getTable() + "\"";
    }

    private VectorIndexResultView toView(DocumentVectorIndexPO po, int chunkCount,
                                         int processed, boolean idempotent) {
        VectorIndexResultView view = new VectorIndexResultView();
        view.setDocumentId(po.getDocumentId());
        view.setVersion(po.getVersion());
        view.setStrategy(po.getStrategy());
        view.setStatus(po.getStatus());
        view.setStage(po.getStage());
        view.setChunkCount(chunkCount);
        view.setProcessed(processed);
        view.setIdempotent(idempotent);
        view.setErrorCode(po.getErrorCode());
        view.setErrorMessage(po.getErrorMessage());
        view.setAttemptCount(po.getAttemptCount());
        return view;
    }

    private String safeMessage(Exception ex) {
        return safeMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    private String safeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > MAX_ERROR_MESSAGE
                ? message.substring(0, MAX_ERROR_MESSAGE)
                : message;
    }

    /**
     * 预处理后的 Chunk 三元组。
     */
    private record PreparedChunk(Chunk chunk, String chunkContentHash, String chunkId) {
    }
}
