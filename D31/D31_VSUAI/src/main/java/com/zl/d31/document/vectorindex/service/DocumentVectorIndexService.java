package com.zl.d31.document.vectorindex.service;

import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.chunk.model.ChunkStrategy;
import com.zl.d31.document.chunk.service.DocumentChunkService;
import com.zl.d31.document.mapper.DocumentChunkIndexMapper;
import com.zl.d31.document.mapper.DocumentVectorIndexMapper;
import com.zl.d31.document.service.DocumentPageService;
import com.zl.d31.document.po.DocumentChunkIndexPO;
import com.zl.d31.document.po.DocumentVectorIndexPO;
import com.zl.d31.document.support.Sha256ContentHasher;
import com.zl.d31.document.vectorindex.VectorIndexException;
import com.zl.d31.document.vectorindex.config.VectorIndexProperties;
import com.zl.d31.document.vectorindex.dto.VectorIndexResultView;
import com.zl.d31.document.vectorindex.model.ChunkRowStatus;
import com.zl.d31.document.vectorindex.model.IndexStage;
import com.zl.d31.document.vectorindex.model.VectorIndexStatus;
import com.zl.d31.document.vectorindex.support.StableChunkIdGenerator;
import com.zl.d31.document.vectorindex.support.VectorIndexStateTx;
import com.zl.d31.document.vectorstore.PgVectorStoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档向量索引编排服务：D31 核心。
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
                                      ObjectProvider<DocumentChunkService> chunkServiceProvider) {
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

        // 重复导入幂等短路：已完成且指纹与关联数一致，不再产生写入
        if (existing != null
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
                            .metadata(buildMetadata(pc))
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
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int from = 0; from < ids.size(); from += batchSize) {
            int to = Math.min(from + batchSize, ids.size());
            vectorStore.delete(ids.subList(from, to));
        }
        stateTx.deleteDocumentRows(documentId);
        log.info("文档向量已删除 documentId={} vectors={}", documentId, ids.size());
    }

    // ------------------------------------------------------------------
    // 卡住恢复
    // ------------------------------------------------------------------

    /**
     * 恢复卡住任务：扫描 PENDING/INDEXING 且超过 stuckMinutes 的行，
     * 依据 Outbox 中的 Chunk 快照重新幂等入库。
     *
     * @return 实际恢复的任务结果
     */
    public List<VectorIndexResultView> recoverStuckTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getStuckMinutes());
        List<DocumentVectorIndexPO> stuck = indexMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocumentVectorIndexPO>()
                        .in("status",
                                VectorIndexStatus.PENDING.name(),
                                VectorIndexStatus.INDEXING.name())
                        .lt("updated_at", cutoff));
        List<VectorIndexResultView> results = new ArrayList<>();
        for (DocumentVectorIndexPO task : stuck) {
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
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocumentVectorIndexPO>()
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

    private void cleanupSuperseded(String documentId) {
        List<DocumentVectorIndexPO> superseded = indexMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .eq("status", VectorIndexStatus.SUPERSEDED.name()));
        for (DocumentVectorIndexPO old : superseded) {
            try {
                List<DocumentChunkIndexPO> oldRows =
                        chunkMapper.selectByDocumentVersion(documentId, old.getVersion());
                List<String> oldIds = new ArrayList<>(oldRows.size());
                for (DocumentChunkIndexPO r : oldRows) {
                    oldIds.add(r.getChunkId());
                }
                int batchSize = Math.max(1, properties.getBatchSize());
                for (int from = 0; from < oldIds.size(); from += batchSize) {
                    int to = Math.min(from + batchSize, oldIds.size());
                    vectorStore.delete(oldIds.subList(from, to));
                }
                stateTx.deleteOldVersionRows(documentId, old.getVersion());
            } catch (Exception cleanupEx) {
                // 非致命：新版本已活动，记录并留给核对补偿
                log.warn("旧版本清理失败，等待核对补偿 documentId={} version={} reason={}",
                        documentId, old.getVersion(), safeMessage(cleanupEx));
            }
        }
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

    private DocumentVectorIndexPO findIndexRow(String documentId, Integer version) {
        List<DocumentVectorIndexPO> rows = indexMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .eq("version", version));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<DocumentVectorIndexPO> findOlderCompleted(String documentId, Integer version) {
        return indexMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .ne("version", version)
                        .eq("status", VectorIndexStatus.COMPLETED.name()));
    }

    private Map<String, Object> buildMetadata(PreparedChunk pc) {
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
