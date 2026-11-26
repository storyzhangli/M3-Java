package com.zl.d43.one.index;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d43.access.DocumentScope;
import com.zl.d43.document.chunk.model.Chunk;
import com.zl.d43.document.chunk.model.ChunkStrategy;
import com.zl.d43.document.mapper.KnowledgeDocumentMapper;
import com.zl.d43.document.po.KnowledgeDocumentPO;
import com.zl.d43.document.support.Sha256ContentHasher;
import com.zl.d43.document.vectorindex.VectorIndexException;
import com.zl.d43.document.vectorindex.config.VectorIndexProperties;
import com.zl.d43.document.vectorindex.model.ChunkRowStatus;
import com.zl.d43.document.vectorindex.model.IndexStage;
import com.zl.d43.document.vectorindex.model.VectorIndexStatus;
import com.zl.d43.document.vectorindex.support.StableChunkIdGenerator;
import com.zl.d43.one.config.OneEmbeddingProperties;
import com.zl.d43.one.config.OnePgVectorProperties;
import com.zl.d43.one.index.OneVectorIndexStateTx;
import com.zl.d43.one.index.dto.OneVectorIndexResultView;
import com.zl.d43.one.index.mapper.OneDocumentChunkIndexMapper;
import com.zl.d43.one.index.mapper.OneDocumentVectorIndexMapper;
import com.zl.d43.one.index.po.OneDocumentChunkIndexPO;
import com.zl.d43.one.index.po.OneDocumentVectorIndexPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * one_ 轨文档向量索引编排服务（阿里百炼远程 Embedding）。
 *
 * <p>语义对齐 local 轨 {@code DocumentVectorIndexService}，代码独立、表独立、模型独立：</p>
 * <ul>
 *   <li>幂等短路：COMPLETED + 同 payloadHash + 关联数一致 → 零写入零远程调用；</li>
 *   <li>稳定 chunkId 复用 {@link StableChunkIdGenerator}（两轨同逻辑 chunk 同 UUID，按表独立唯一）；</li>
 *   <li>写后三方核对：pgCount == relationCount == chunkCount，否则 COUNT_MISMATCH/PARTIAL；</li>
 *   <li>版本切换先写后切（SUPERSEDED + cleanup），失败不丢旧版本；</li>
 *   <li>元数据额外写入 vectorSpaceId，跨空间复用拒绝的依据之一。</li>
 * </ul>
 *
 * <p>D37 教训继承：同文档换 strategy 重索引必须先 DELETE 再重建
 * （自然键含 strategy 且 upsert 不更新 chunk_id）；直接入库 API 无 page/section
 * 承载字段 → PG 侧 page=null，如实记录不伪造。</p>
 *
 * <p>远程调用成本：每批 Chunk 经 OneRemoteEmbeddingModel 按 maxBatch=16 分批真实调用；
 * 幂等短路不产生调用。页表链路与卡住恢复本轮不在 one_ 轨提供（留 local 轨）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneDocumentVectorIndexService {

    private static final int MAX_ERROR_MESSAGE = 480;

    private final VectorStore oneVectorStore;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final OnePgVectorProperties onePgProperties;
    private final OneEmbeddingProperties oneEmbeddingProperties;
    private final OneDocumentVectorIndexMapper indexMapper;
    private final OneDocumentChunkIndexMapper chunkMapper;
    private final StableChunkIdGenerator idGenerator;
    private final Sha256ContentHasher hasher;
    private final VectorIndexProperties properties;
    private final OneVectorIndexStateTx stateTx;

    /** P2 追加（代码就位，真实 one_ 链路 NOT RUN）：文档权限三列读取，与 local 轨同源。 */
    private final ObjectProvider<KnowledgeDocumentMapper> documentMapperProvider;

    public OneDocumentVectorIndexService(@Qualifier("onePgVectorStore") VectorStore oneVectorStore,
                                         @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                         OnePgVectorProperties onePgProperties,
                                         OneEmbeddingProperties oneEmbeddingProperties,
                                         OneDocumentVectorIndexMapper indexMapper,
                                         OneDocumentChunkIndexMapper chunkMapper,
                                         StableChunkIdGenerator idGenerator,
                                         Sha256ContentHasher hasher,
                                         VectorIndexProperties properties,
                                         OneVectorIndexStateTx stateTx,
                                         ObjectProvider<KnowledgeDocumentMapper> documentMapperProvider) {
        this.oneVectorStore = oneVectorStore;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.onePgProperties = onePgProperties;
        this.oneEmbeddingProperties = oneEmbeddingProperties;
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
        this.idGenerator = idGenerator;
        this.hasher = hasher;
        this.properties = properties;
        this.stateTx = stateTx;
        this.documentMapperProvider = documentMapperProvider;
    }

    /** P2：读取文档权限三列（与 local 轨同语义：无行/mapper 缺席/未打标 → null）。 */
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

    /**
     * 对给定 Chunk 集合执行幂等向量化入库（one_ 轨）。
     */
    public OneVectorIndexResultView indexChunks(String documentId, Integer version,
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
        List<Chunk> normalized = normalizeChunks(documentId, targetVersion, targetStrategy, title, chunks);
        String spaceId = oneEmbeddingProperties.vectorSpaceId();
        // P2（代码就位）：入库前读取文档权限三列，与 local 轨同源同语义
        DocumentScope permission = lookupDocumentScope(documentId);

        LocalDateTime now = LocalDateTime.now();
        OneDocumentVectorIndexPO existing = findIndexRow(documentId, targetVersion);
        String payloadHash = hasher.hash(buildPayloadText(normalized));

        // 重复导入幂等短路：已完成且指纹与关联数一致，零写入零远程调用
        if (existing != null
                && VectorIndexStatus.COMPLETED.name().equals(existing.getStatus())
                && payloadHash.equals(existing.getContentHash())
                && chunkMapper.selectByDocumentVersion(documentId, targetVersion).size() == normalized.size()) {
            log.info("one_ 重复导入命中幂等，documentId={}, version={}", documentId, targetVersion);
            return toView(existing, normalized.size(), 0, true);
        }

        int attempt = existing == null ? 1 : existing.getAttemptCount() + 1;
        OneDocumentVectorIndexPO task = existing != null ? existing : new OneDocumentVectorIndexPO();
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
        task.setVectorSpaceId(spaceId);
        if (task.getId() == null) {
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            try {
                indexMapper.upsertOnNaturalKey(task);
            } catch (org.springframework.dao.DuplicateKeyException concurrentCreate) {
                OneDocumentVectorIndexPO winner = findIndexRow(documentId, targetVersion);
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
                task.setAttemptCount(winner.getAttemptCount() == null ? 1 : winner.getAttemptCount() + 1);
                task.setNextRetryAt(null);
                task.setVectorSpaceId(spaceId);
                persistState(task, LocalDateTime.now());
            }
        } else {
            persistState(task, now);
        }

        // 稳定 ID 与内容指纹（与 local 轨同逻辑 chunk 同 UUID）
        List<PreparedChunk> prepared = new ArrayList<>(normalized.size());
        for (Chunk chunk : normalized) {
            String chunkHash = hasher.hash(chunk.getContent());
            String chunkId = idGenerator.generate(chunk, chunkHash, properties.getSplitVersion());
            prepared.add(new PreparedChunk(chunk, chunkHash, chunkId));
        }

        // 分批 Embedding + Upsert（远程真实调用在 OneRemoteEmbeddingModel 内按 maxBatch 分批）
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
                            .metadata(buildMetadata(pc, spaceId, permission))
                            .build());
                }
                oneVectorStore.add(documents);
                LocalDateTime rowTime = LocalDateTime.now();
                for (PreparedChunk pc : batch) {
                    chunkMapper.upsertOnNaturalKey(toChunkPO(pc, rowTime, spaceId));
                }
                processed += batch.size();
                task.setStage(IndexStage.EMBED_WRITE.name());
                task.setUpdatedAt(LocalDateTime.now());
                indexMapper.updateById(task);
            }
        } catch (Exception ex) {
            handleWriteFailure(task, processed, ex);
            throw new VectorIndexException(task.getErrorCode(),
                    "one_ 向量写入失败，已持久化现场可恢复: " + safeMessage(ex), ex);
        }

        // 写后三方核对
        long pgCount = countPgForVersion(documentId, targetVersion);
        int relationCount = chunkMapper.selectByDocumentVersion(documentId, targetVersion).size();
        if (pgCount != normalized.size() || relationCount != normalized.size()) {
            task.setStatus(VectorIndexStatus.PARTIAL.name());
            task.setStage(IndexStage.EMBED_WRITE.name());
            task.setErrorCode("COUNT_MISMATCH");
            task.setErrorMessage(safeMessage(
                    "核对不一致 pg=%d relation=%d expected=%d".formatted(pgCount, relationCount, normalized.size())));
            task.setNextRetryAt(LocalDateTime.now().plusSeconds(1));
            persistState(task, LocalDateTime.now());
            throw new VectorIndexException("COUNT_MISMATCH", "one_ 写入后核对不一致，等待核对补偿", null);
        }

        // 版本切换 + 旧版本清理
        List<OneDocumentVectorIndexPO> olderCompleted = findOlderCompleted(documentId, targetVersion);
        LocalDateTime switchTime = LocalDateTime.now();
        task.setStage(IndexStage.SWITCH.name());
        indexMapper.updateById(task);
        stateTx.switchVersion(task, olderCompleted, switchTime);
        cleanupSuperseded(documentId);

        task.setStatus(VectorIndexStatus.COMPLETED.name());
        task.setStage(IndexStage.CLEANUP.name());
        task.setErrorCode("");
        task.setErrorMessage("");
        task.setNextRetryAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        indexMapper.updateById(task);

        log.info("one_ 向量索引完成 documentId={} version={} chunks={} attempt={} space={}",
                documentId, targetVersion, normalized.size(), attempt, spaceId);
        return toView(task, normalized.size(), processed, false);
    }

    /** 按文档删除全部版本 one_ 向量与关联：分批、可重复执行。 */
    public void deleteByDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        List<OneDocumentChunkIndexPO> rows = chunkMapper.selectByDocumentId(documentId);
        List<String> ids = new ArrayList<>(rows.size());
        for (OneDocumentChunkIndexPO row : rows) {
            ids.add(row.getChunkId());
        }
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int from = 0; from < ids.size(); from += batchSize) {
            int to = Math.min(from + batchSize, ids.size());
            oneVectorStore.delete(ids.subList(from, to));
        }
        stateTx.deleteDocumentRows(documentId);
        log.info("one_ 文档向量已删除 documentId={} vectors={}", documentId, ids.size());
    }

    public List<OneDocumentVectorIndexPO> getStatus(String documentId) {
        return indexMapper.selectList(new QueryWrapper<OneDocumentVectorIndexPO>()
                .eq("document_id", documentId).orderByAsc("version"));
    }

    public List<OneDocumentChunkIndexPO> listChunks(String documentId) {
        return chunkMapper.selectByDocumentId(documentId);
    }

    /** 按稳定 ID 直接删除 one_ PG 向量（补偿删除入口，重复执行安全）。 */
    public void deleteVectorsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        oneVectorStore.delete(ids);
    }

    /** one_ PG 表中指定文档版本的向量数（按 metadata 过滤，只查 one_ 表）。 */
    public long countPgForVersion(String documentId, Integer version) {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTable()
                + " WHERE (metadata->>'documentId') = ? AND (metadata->>'version') = ?";
        Long count = pgVectorJdbcTemplate.queryForObject(sql, Long.class, documentId, String.valueOf(version));
        return count == null ? 0L : count;
    }

    /** one_ PG 表中指定文档版本的向量 ID 集合（核对用）。 */
    public List<String> listPgIdsForVersion(String documentId, Integer version) {
        String sql = "SELECT id FROM " + qualifiedTable()
                + " WHERE (metadata->>'documentId') = ? AND (metadata->>'version') = ?";
        return pgVectorJdbcTemplate.queryForList(sql, String.class, documentId, String.valueOf(version));
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private void cleanupSuperseded(String documentId) {
        List<OneDocumentVectorIndexPO> superseded = indexMapper.selectList(
                new QueryWrapper<OneDocumentVectorIndexPO>()
                        .eq("document_id", documentId)
                        .eq("status", VectorIndexStatus.SUPERSEDED.name()));
        for (OneDocumentVectorIndexPO old : superseded) {
            try {
                List<OneDocumentChunkIndexPO> oldRows =
                        chunkMapper.selectByDocumentVersion(documentId, old.getVersion());
                List<String> oldIds = new ArrayList<>(oldRows.size());
                for (OneDocumentChunkIndexPO r : oldRows) {
                    oldIds.add(r.getChunkId());
                }
                int batchSize = Math.max(1, properties.getBatchSize());
                for (int from = 0; from < oldIds.size(); from += batchSize) {
                    int to = Math.min(from + batchSize, oldIds.size());
                    oneVectorStore.delete(oldIds.subList(from, to));
                }
                stateTx.deleteOldVersionRows(documentId, old.getVersion());
            } catch (Exception cleanupEx) {
                log.warn("one_ 旧版本清理失败，等待核对补偿 documentId={} version={} reason={}",
                        documentId, old.getVersion(), safeMessage(cleanupEx));
            }
        }
    }

    private void handleWriteFailure(OneDocumentVectorIndexPO task, int processed, Exception ex) {
        task.setStatus(processed > 0 ? VectorIndexStatus.PARTIAL.name() : VectorIndexStatus.FAILED.name());
        task.setStage(IndexStage.EMBED_WRITE.name());
        task.setErrorCode("EMBED_WRITE_FAILED");
        task.setErrorMessage(safeMessage(ex));
        task.setNextRetryAt(LocalDateTime.now().plusNanos(properties.getRetryBackoffMs() * 1_000_000L));
        persistState(task, LocalDateTime.now());
        log.warn("one_ 向量写入中断 documentId={} version={} processed={} reason={}",
                task.getDocumentId(), task.getVersion(), processed, safeMessage(ex));
    }

    private void persistState(OneDocumentVectorIndexPO task, LocalDateTime now) {
        indexMapper.updateState(task.getDocumentId(), task.getVersion(),
                task.getStatus(), task.getStage(), task.getErrorCode(), task.getErrorMessage(),
                task.getAttemptCount(), task.getNextRetryAt(), task.getChunkCount(), now);
    }

    private OneDocumentVectorIndexPO findIndexRow(String documentId, Integer version) {
        List<OneDocumentVectorIndexPO> rows = indexMapper.selectList(
                new QueryWrapper<OneDocumentVectorIndexPO>()
                        .eq("document_id", documentId).eq("version", version));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<OneDocumentVectorIndexPO> findOlderCompleted(String documentId, Integer version) {
        return indexMapper.selectList(new QueryWrapper<OneDocumentVectorIndexPO>()
                .eq("document_id", documentId).ne("version", version)
                .eq("status", VectorIndexStatus.COMPLETED.name()));
    }

    private Map<String, Object> buildMetadata(PreparedChunk pc, String spaceId, DocumentScope permission) {
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
        // one_ 轨专属：向量空间绑定（跨空间复用拒绝依据）
        metadata.put("vectorSpaceId", spaceId);
        // P2（D2 裁决）：权限三键有值才写（含显式 '*'）；无标签不写键，缺失键谓词天然排除
        if (permission != null) {
            if (permission.tenantId() != null && !permission.tenantId().isBlank()) {
                metadata.put("tenantId", permission.tenantId());
            }
            if (permission.deptId() != null && !permission.deptId().isBlank()) {
                metadata.put("deptId", permission.deptId());
            }
            if (permission.deviceId() != null && !permission.deviceId().isBlank()) {
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

    private OneDocumentChunkIndexPO toChunkPO(PreparedChunk pc, LocalDateTime time, String spaceId) {
        OneDocumentChunkIndexPO po = new OneDocumentChunkIndexPO();
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
        po.setVectorSpaceId(spaceId);
        po.setCreatedAt(time);
        po.setUpdatedAt(time);
        return po;
    }

    private List<Chunk> normalizeChunks(String documentId, int version,
                                        ChunkStrategy strategy, String title, List<Chunk> source) {
        List<Chunk> result = new ArrayList<>(source.size());
        for (Chunk original : source) {
            if (original == null || original.getContent() == null || original.getContent().isBlank()) {
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
            chunk.setCodePointCount(original.getContent().codePointCount(0, original.getContent().length()));
            chunk.setGlobalStart(original.getGlobalStart());
            chunk.setGlobalEnd(original.getGlobalEnd());
            chunk.setSources(original.getSources());
            result.add(chunk);
        }
        result.sort(Comparator.comparingInt(Chunk::getChunkIndex));
        return result;
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
        return "\"" + onePgProperties.getSchema() + "\".\"" + onePgProperties.getTable() + "\"";
    }

    private OneVectorIndexResultView toView(OneDocumentVectorIndexPO po, int chunkCount,
                                            int processed, boolean idempotent) {
        OneVectorIndexResultView view = new OneVectorIndexResultView();
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
        view.setVectorSpaceId(po.getVectorSpaceId());
        return view;
    }

    private String safeMessage(Exception ex) {
        return safeMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
    }

    private String safeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > MAX_ERROR_MESSAGE ? message.substring(0, MAX_ERROR_MESSAGE) : message;
    }

    private record PreparedChunk(Chunk chunk, String chunkContentHash, String chunkId) {
    }
}