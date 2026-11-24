package com.zl.d41.document.vectorindex.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d41.document.chunk.model.Chunk;
import com.zl.d41.document.mapper.DocumentChunkIndexMapper;
import com.zl.d41.document.mapper.DocumentVectorIndexMapper;
import com.zl.d41.document.po.DocumentChunkIndexPO;
import com.zl.d41.document.po.DocumentVectorIndexPO;
import com.zl.d41.document.po.KnowledgeDocumentPO;
import com.zl.d41.document.sync.model.SyncReasonCode;
import com.zl.d41.document.sync.model.SyncStatus;
import com.zl.d41.document.sync.po.DocumentSyncPO;
import com.zl.d41.document.vectorindex.config.VectorIndexProperties;
import com.zl.d41.document.vectorindex.dto.ReconciliationReportView;
import com.zl.d41.document.vectorindex.model.VectorIndexStatus;
import com.zl.d41.document.vectorindex.support.VectorIndexStateTx;
import com.zl.d41.document.vectorstore.PgVectorStoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 跨系统一致性核对与补偿服务。
 *
 * <p>以真实数据库为唯一事实来源，核对 MySQL（原文/状态库）与 PG（向量库）：
 * 孤儿向量、缺失向量、卡住任务、数量不一致。dryRun 只出报告；
 * 非 dryRun 执行补偿删除（孤儿）与幂等重建（缺失/卡住），不 Mock 数据库。</p>
 *
 * <p>不引入分布式事务：补偿动作复用 {@link DocumentVectorIndexService} 的
 * 稳定 ID 与 Upsert，保证重复执行安全。</p>
 *
 * <p><b>P4 扩展</b>（任务书 §2 P4，追加式）：</p>
 * <ul>
 *   <li>既有四类显式标类：DRIFT_META_ONLY（missing）/DRIFT_VECTOR_ONLY（orphan 一般）/
 *       DRIFT_STALE_VERSION（orphan 中所属任务=SUPERSEDED 子集）；</li>
 *   <li>新增 DRIFT_VERSION_MISMATCH：(a) knowledge_document.version ≠ 活动 COMPLETED 版本；
 *       (b) 同一 chunkId 的 PG metadata.version ≠ 关联行 version；</li>
 *   <li>新增 DRIFT_SYNC_STUCK：sync FAILED 到期/耗尽 或 PENDING 滞留超 stuckMinutes；</li>
 *   <li>修复全走管线（H4）：missing→indexChunks 快照重建；orphan/stale→deleteVectorsByIds
 *       补偿删除；version(a)→{@code stateTx.repointVersionPointer}（SWITCH 同型指针事务）；
 *       version(b)→活动版本快照幂等重建；syncStuck→/sync/retry 手动端点（不在本服务内执行）；</li>
 *   <li>repairDocumentPrefix：非空时补偿只作用于该前缀（H6 夹具域隔离；检测始终全域）。</li>
 * </ul>
 */
@Slf4j
@Service
public class VectorReconciliationService {

    /** 漂移标类（KP4 冻结字符串，测试与脚本按此断言）。 */
    public static final String DRIFT_META_ONLY = "DRIFT_META_ONLY";
    public static final String DRIFT_VECTOR_ONLY = "DRIFT_VECTOR_ONLY";
    public static final String DRIFT_STALE_VERSION = "DRIFT_STALE_VERSION";
    public static final String DRIFT_VERSION_MISMATCH = "DRIFT_VERSION_MISMATCH";
    public static final String DRIFT_SYNC_STUCK = "DRIFT_SYNC_STUCK";
    public static final String CAT_COUNT_MISMATCH = "COUNT_MISMATCH";
    public static final String CAT_STUCK_TASK = "STUCK_TASK";

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorStoreProperties pgProperties;

    private final DocumentVectorIndexMapper indexMapper;

    private final DocumentChunkIndexMapper chunkMapper;

    private final DocumentVectorIndexService indexService;

    private final VectorIndexProperties properties;

    private final ObjectMapper objectMapper;

    /** P4 追加：文档元数据版本权威列（DRIFT_VERSION_MISMATCH a 路判定）。 */
    private final com.zl.d41.document.mapper.KnowledgeDocumentMapper documentMapper;

    /** P4 追加：操作级同步表（DRIFT_SYNC_STUCK 判定）。 */
    private final com.zl.d41.document.sync.mapper.DocumentSyncMapper syncMapper;

    /** P4 追加：版本指针修复走既有本地事务管线（H4，禁裸 SQL）。 */
    private final VectorIndexStateTx stateTx;

    public VectorReconciliationService(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            PgVectorStoreProperties pgProperties,
            DocumentVectorIndexMapper indexMapper,
            DocumentChunkIndexMapper chunkMapper,
            DocumentVectorIndexService indexService,
            VectorIndexProperties properties,
            ObjectMapper objectMapper,
            com.zl.d41.document.mapper.KnowledgeDocumentMapper documentMapper,
            com.zl.d41.document.sync.mapper.DocumentSyncMapper syncMapper,
            VectorIndexStateTx stateTx) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.pgProperties = pgProperties;
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
        this.indexService = indexService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.documentMapper = documentMapper;
        this.syncMapper = syncMapper;
        this.stateTx = stateTx;
    }

    /**
     * 执行核对（兼容既有端点契约：修复全域）。
     *
     * <p><b>风险提示（状态文件+文档留痕）</b>：MySQL 共享表存在跨 Dxx 遗留行
     * （如 d32eval-*，向量属历史表资产），全局 {@code dryRun=false} 会把遗留关联行
     * 判缺失并重建进<b>当前</b> PG 表——本轮一律经 /sync/consistency 带前缀修复，
     * 全局非 dryRun 仅保留给开发态手工调用，列 D41 收口。</p>
     */
    public ReconciliationReportView reconcile(boolean dryRun) {
        return reconcile(dryRun, "");
    }

    /**
     * P4：执行核对（修复域可限定）。
     *
     * @param dryRun               true 只出报告；false 执行补偿
     * @param repairDocumentPrefix 非空时补偿动作只作用于该前缀文档（H6；检测始终全域）
     */
    public ReconciliationReportView reconcile(boolean dryRun, String repairDocumentPrefix) {
        String scope = repairDocumentPrefix == null ? "" : repairDocumentPrefix.trim();
        ReconciliationReportView report = new ReconciliationReportView();
        report.setDryRun(dryRun);
        report.setScopePrefix(scope);

        List<PgRow> pgRows = loadPgRows();
        report.setPgTotalRows(pgRows.size());

        List<DocumentChunkIndexPO> allRelations = chunkMapper.selectList(null);
        List<DocumentVectorIndexPO> allTasks = indexMapper.selectList(null);

        Map<String, DocumentChunkIndexPO> relationByChunkId = new HashMap<>();
        for (DocumentChunkIndexPO po : allRelations) {
            relationByChunkId.put(po.getChunkId(), po);
        }
        Map<String, DocumentVectorIndexPO> taskByKey = new HashMap<>();
        for (DocumentVectorIndexPO po : allTasks) {
            taskByKey.put(taskKey(po.getDocumentId(), po.getVersion()), po);
        }

        Set<String> pgIds = new HashSet<>();
        List<String> orphanIds = new ArrayList<>();

        // 1) 孤儿向量：PG 有，但无关联行，或所属任务非活动/已取代
        //    标类：所属任务=SUPERSEDED → DRIFT_STALE_VERSION（orphan 子集显式分类，D5）；
        //    其余（无关联/无任务/FAILED 非活动）→ DRIFT_VECTOR_ONLY
        for (PgRow row : pgRows) {
            pgIds.add(row.id());
            String documentId = String.valueOf(row.metadata().get("documentId"));
            Integer version = parseVersion(row.metadata().get("version"));
            DocumentChunkIndexPO relation = relationByChunkId.get(row.id());
            DocumentVectorIndexPO task = taskByKey.get(taskKey(documentId, version));
            if (relation == null || task == null || !isActive(task.getStatus())) {
                boolean stale = task != null
                        && VectorIndexStatus.SUPERSEDED.name().equals(task.getStatus());
                report.getOrphanVectors().add(new ReconciliationReportView.Issue(
                        row.id(), documentId, version,
                        stale ? "SUPERSEDED 旧版本向量残留（orphan 子集，sync UPDATE_CLEANUP 域）"
                              : "PG 向量无有效关联或所属任务非活动状态",
                        stale ? DRIFT_STALE_VERSION : DRIFT_VECTOR_ONLY));
                orphanIds.add(row.id());
            }
        }

        // 2) 缺失向量：关联行存在但 PG 无对应行（标类 DRIFT_META_ONLY）
        Map<String, List<DocumentChunkIndexPO>> missingByVersion = new HashMap<>();
        for (DocumentChunkIndexPO po : allRelations) {
            if (!pgIds.contains(po.getChunkId())) {
                report.getMissingVectors().add(new ReconciliationReportView.Issue(
                        po.getChunkId(), po.getDocumentId(), po.getVersion(),
                        "关联行存在但 PG 缺失向量", DRIFT_META_ONLY));
                missingByVersion.computeIfAbsent(
                        taskKey(po.getDocumentId(), po.getVersion()), k -> new ArrayList<>()).add(po);
            }
        }

        // 3) 数量不一致：版本级关联数 vs PG 数
        Map<String, List<DocumentChunkIndexPO>> relationByVersion = new HashMap<>();
        for (DocumentChunkIndexPO po : allRelations) {
            relationByVersion.computeIfAbsent(
                    taskKey(po.getDocumentId(), po.getVersion()), k -> new ArrayList<>()).add(po);
        }
        for (Map.Entry<String, List<DocumentChunkIndexPO>> entry : relationByVersion.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String documentId = parts[0];
            Integer version = Integer.valueOf(parts[1]);
            long pgCount = indexService.countPgForVersion(documentId, version);
            if (pgCount != entry.getValue().size()) {
                report.getCountMismatches().add(new ReconciliationReportView.Issue(
                        entry.getKey(), documentId, version,
                        "关联数=%d 而 PG 数=%d".formatted(entry.getValue().size(), pgCount),
                        CAT_COUNT_MISMATCH));
            }
        }

        // 4) 卡住任务（索引态）
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getStuckMinutes());
        for (DocumentVectorIndexPO po : allTasks) {
            if ((VectorIndexStatus.PENDING.name().equals(po.getStatus())
                    || VectorIndexStatus.INDEXING.name().equals(po.getStatus()))
                    && po.getUpdatedAt() != null && po.getUpdatedAt().isBefore(cutoff)) {
                report.getStuckTasks().add(new ReconciliationReportView.Issue(
                        taskKey(po.getDocumentId(), po.getVersion()),
                        po.getDocumentId(), po.getVersion(),
                        "任务停留在 " + po.getStatus() + " 超过 " + properties.getStuckMinutes() + " 分钟",
                        CAT_STUCK_TASK));
            }
        }

        // 5) 新增 DRIFT_VERSION_MISMATCH：
        //    (a) knowledge_document.version ≠ 该文档最新 COMPLETED 任务版本（无 COMPLETED 行跳过不误报）
        Map<String, Integer> activeVersionByDoc = new HashMap<>();
        for (DocumentVectorIndexPO po : allTasks) {
            if (VectorIndexStatus.COMPLETED.name().equals(po.getStatus())
                    && po.getVersion() != null) {
                activeVersionByDoc.merge(po.getDocumentId(), po.getVersion(), Math::max);
            }
        }
        for (KnowledgeDocumentPO doc : documentMapper.selectList(null)) {
            Integer active = activeVersionByDoc.get(doc.getDocumentId());
            if (active != null && doc.getVersion() != null && !active.equals(doc.getVersion())) {
                report.getVersionMismatches().add(new ReconciliationReportView.Issue(
                        taskKey(doc.getDocumentId(), active), doc.getDocumentId(), active,
                        "knowledge_document.version=%d ≠ 活动 COMPLETED 版本=%d（指针漂移，可 repoint 修复）"
                                .formatted(doc.getVersion(), active),
                        DRIFT_VERSION_MISMATCH));
            }
        }
        //    (b) 同一 chunkId 的 PG metadata.version ≠ 关联行 version
        for (PgRow row : pgRows) {
            DocumentChunkIndexPO relation = relationByChunkId.get(row.id());
            if (relation == null) {
                continue; // 无关联行已归 orphan，不重复报
            }
            Integer pgVersion = parseVersion(row.metadata().get("version"));
            if (pgVersion != null && relation.getVersion() != null
                    && !pgVersion.equals(relation.getVersion())) {
                report.getVersionMismatches().add(new ReconciliationReportView.Issue(
                        row.id(), relation.getDocumentId(), relation.getVersion(),
                        "PG metadata.version=%d ≠ 关联行 version=%d（快照漂移，重索引活动版本可修复）"
                                .formatted(pgVersion, relation.getVersion()),
                        DRIFT_VERSION_MISMATCH));
            }
        }

        // 6) 新增 DRIFT_SYNC_STUCK：FAILED 到期/耗尽；PENDING 滞留超 stuckMinutes
        LocalDateTime now = LocalDateTime.now();
        for (DocumentSyncPO syncRow : syncMapper.selectList(null)) {
            String status = syncRow.getSyncStatus();
            String reason = syncRow.getReasonCode() == null ? "" : syncRow.getReasonCode();
            boolean stuck =
                    (SyncStatus.FAILED.name().equals(status)
                            && (syncRow.getNextRetryAt() == null
                                || !syncRow.getNextRetryAt().isAfter(now)
                                || reason.equals(SyncReasonCode.RETRY_EXHAUSTED.name())))
                    || (SyncStatus.PENDING.name().equals(status)
                        && syncRow.getUpdatedAt() != null
                        && syncRow.getUpdatedAt().isBefore(cutoff));
            if (stuck) {
                report.getSyncStuck().add(new ReconciliationReportView.Issue(
                        syncRow.getDocumentId() + "|" + syncRow.getOperation()
                                + "|" + syncRow.getVersion(),
                        syncRow.getDocumentId(), syncRow.getVersion(),
                        "sync 行滞留 status=%s reason=%s attempt=%d，/sync/retry 可收敛"
                                .formatted(status, reason,
                                        syncRow.getAttemptCount() == null ? 0 : syncRow.getAttemptCount()),
                        DRIFT_SYNC_STUCK));
            }
        }

        if (!dryRun) {
            applyCompensation(report, scope, orphanIds, missingByVersion);
        }
        report.refreshDriftSummary();
        log.info("核对完成 dryRun={} scope={} issues={} (orphan={}, missing={}, mismatch={}, stuck={}, version={}, syncStuck={})",
                dryRun, scope, report.totalIssues(),
                report.getOrphanVectors().size(), report.getMissingVectors().size(),
                report.getCountMismatches().size(), report.getStuckTasks().size(),
                report.getVersionMismatches().size(), report.getSyncStuck().size());
        return report;
    }

    /**
     * 非破坏性 page metadata 覆盖度核对（只读，不删除、不重写）。
     *
     * <p>用于标记缺 page 的旧向量并记录覆盖范围：新入库/重索引向量带真实 page，
     * 历史向量 page 保持 null；回填的唯一支持路径是经入库流水线重索引，
     * 本方法不做魔法补丁，也不推断页码。</p>
     *
     * @return 覆盖度统计（含缺失 page 的向量 id）
     */
    public PageCoverage pageMetadataCoverage() {
        List<PgRow> rows = loadPgRows();
        int withPage = 0;
        List<String> missingPageIds = new ArrayList<>();
        for (PgRow row : rows) {
            Object page = row.metadata().get("page");
            if (page instanceof Number) {
                withPage++;
            } else {
                missingPageIds.add(row.id());
            }
        }
        log.info("page metadata 覆盖度: total={}, withPage={}, missingPage={}",
                rows.size(), withPage, missingPageIds.size());
        return new PageCoverage(rows.size(), withPage, List.copyOf(missingPageIds));
    }

    /** page metadata 覆盖度统计。 */
    public record PageCoverage(int totalVectors, int vectorsWithPage, List<String> missingPageIds) {

        /** 带 page 向量占比；总量为 0 时返回 0（调用方可据此标注 N/A）。 */
        public double coverageRatio() {
            return totalVectors == 0 ? 0.0d : (double) vectorsWithPage / totalVectors;
        }
    }

    private void applyCompensation(ReconciliationReportView report, String scope,
                                   List<String> orphanIds,
                                   Map<String, List<DocumentChunkIndexPO>> missingByVersion) {
        // 补偿删除孤儿向量（含 STALE 子集；deleteVectorsByIds=既有补偿管线，H4）
        int batchSize = Math.max(1, properties.getBatchSize());
        int orphanDeleted = 0;
        for (int from = 0; from < orphanIds.size(); from += batchSize) {
            int to = Math.min(from + batchSize, orphanIds.size());
            List<String> batch = new ArrayList<>();
            for (String id : orphanIds.subList(from, to)) {
                if (inScope(findDocIdOfOrphan(report, id), scope)) {
                    batch.add(id);
                }
            }
            if (!batch.isEmpty()) {
                indexService.deleteVectorsByIds(batch);
                orphanDeleted += batch.size();
            }
        }
        if (orphanDeleted > 0) {
            report.getActionsTaken().add("删除孤儿向量 " + orphanDeleted + " 条"
                    + (scope.isEmpty() ? "" : "（scope=" + scope + "）"));
        }

        // 补偿重建缺失向量：按版本用 Outbox 快照幂等重跑（indexChunks=既有入库管线）
        for (Map.Entry<String, List<DocumentChunkIndexPO>> entry : missingByVersion.entrySet()) {
            List<DocumentChunkIndexPO> rows = entry.getValue();
            String documentId = rows.get(0).getDocumentId();
            Integer version = rows.get(0).getVersion();
            if (!inScope(documentId, scope)) {
                continue;
            }
            List<Chunk> chunks = new ArrayList<>(rows.size());
            List<DocumentChunkIndexPO> fullSnapshot =
                    chunkMapper.selectByDocumentVersion(documentId, version);
            for (DocumentChunkIndexPO po : fullSnapshot) {
                chunks.add(toChunk(po));
            }
            indexService.indexChunks(documentId, version,
                    rows.get(0).getStrategy(), null, chunks, true);
            report.getActionsTaken().add(
                    "重建缺失向量 documentId=%s version=%d".formatted(documentId, version));
        }

        // 卡住任务恢复（recoverStuckTasks=既有恢复管线）
        // R01 整改：修复必须具备范围隔离——scope 空串=全量（原语义），非空=仅前缀内恢复，
        // 杜绝跨业务范围（其他 Dxx 域卡住行）被一致性修复意外重索引。
        List<com.zl.d41.document.vectorindex.dto.VectorIndexResultView> recovered =
                indexService.recoverStuckTasks(scope.isEmpty() ? null : scope);
        if (!recovered.isEmpty()) {
            report.getActionsTaken().add("恢复卡住任务 " + recovered.size() + " 个");
        }

        // 版本不一致修复（走管线，H4）：
        //  (a) 指针漂移 → stateTx.repointVersionPointer（SWITCH 同型 MySQL 本地事务）
        //  (b) PG 快照版本漂移 → 活动版本快照幂等重索引（indexChunks upsert 覆盖 metadata）
        for (ReconciliationReportView.Issue issue : report.getVersionMismatches()) {
            if (!inScope(issue.getDocumentId(), scope)) {
                continue;
            }
            if (issue.getKey().contains("|")) {
                // (a) 任务键形态 docId|version → repoint 指针到活动版本
                Integer activeVersion = issue.getVersion();
                stateTx.repointVersionPointer(issue.getDocumentId(), activeVersion,
                        LocalDateTime.now());
                report.getActionsTaken().add(
                        "文档版本指针重放 documentId=%s → v%d（stateTx 本地事务）"
                                .formatted(issue.getDocumentId(), activeVersion));
            } else {
                // (b) chunkId 形态 → 关联行版本快照重建
                DocumentChunkIndexPO relation =
                        chunkMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                                .QueryWrapper<DocumentChunkIndexPO>()
                                .eq("chunk_id", issue.getKey()))
                                .stream().findFirst().orElse(null);
                if (relation != null) {
                    List<DocumentChunkIndexPO> fullSnapshot = chunkMapper.selectByDocumentVersion(
                            relation.getDocumentId(), relation.getVersion());
                    List<Chunk> chunks = new ArrayList<>(fullSnapshot.size());
                    for (DocumentChunkIndexPO po : fullSnapshot) {
                        chunks.add(toChunk(po));
                    }
                    indexService.indexChunks(relation.getDocumentId(), relation.getVersion(),
                            relation.getStrategy(), null, chunks, true);
                    report.getActionsTaken().add(
                            "版本快照重索引 documentId=%s version=%d"
                                    .formatted(relation.getDocumentId(), relation.getVersion()));
                }
            }
        }
    }

    /** 孤儿 id → documentId 反查（scope 过滤用；从已生成报告行取，避免二次 PG 查询）。 */
    private String findDocIdOfOrphan(ReconciliationReportView report, String chunkId) {
        for (ReconciliationReportView.Issue issue : report.getOrphanVectors()) {
            if (chunkId.equals(issue.getKey())) {
                return issue.getDocumentId();
            }
        }
        return "";
    }

    private static boolean inScope(String documentId, String scope) {
        return scope.isEmpty()
                || (documentId != null && documentId.startsWith(scope));
    }

    private List<PgRow> loadPgRows() {
        String sql = "SELECT id, metadata::text FROM " + qualifiedTable();
        return pgVectorJdbcTemplate.query(sql, (rs, rowNum) -> {
            String id = rs.getString(1);
            String metadataText = rs.getString(2);
            Map<String, Object> metadata;
            try {
                metadata = objectMapper.readValue(metadataText,
                        new TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception parseEx) {
                metadata = new HashMap<>();
            }
            return new PgRow(id, metadata);
        });
    }

    private boolean isActive(String status) {
        return VectorIndexStatus.COMPLETED.name().equals(status)
                || VectorIndexStatus.INDEXING.name().equals(status)
                || VectorIndexStatus.PARTIAL.name().equals(status)
                || VectorIndexStatus.PENDING.name().equals(status);
    }

    private Integer parseVersion(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Chunk toChunk(DocumentChunkIndexPO po) {
        Chunk chunk = new Chunk();
        chunk.setDocumentId(po.getDocumentId());
        chunk.setVersion(po.getVersion());
        chunk.setStrategy(com.zl.d41.document.chunk.model.ChunkStrategy.valueOf(po.getStrategy()));
        chunk.setChunkIndex(po.getChunkIndex());
        chunk.setContent(po.getContent());
        chunk.setLength(po.getCharLength());
        chunk.setGlobalStart(po.getGlobalStart());
        chunk.setGlobalEnd(po.getGlobalEnd());
        return chunk;
    }

    private String taskKey(String documentId, Integer version) {
        return documentId + "|" + version;
    }

    private String qualifiedTable() {
        return "\"" + pgProperties.getSchema() + "\".\"" + pgProperties.getTable() + "\"";
    }

    private record PgRow(String id, Map<String, Object> metadata) {
    }
}
