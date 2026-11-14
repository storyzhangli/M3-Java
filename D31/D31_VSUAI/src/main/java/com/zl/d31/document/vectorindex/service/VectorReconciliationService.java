package com.zl.d31.document.vectorindex.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.mapper.DocumentChunkIndexMapper;
import com.zl.d31.document.mapper.DocumentVectorIndexMapper;
import com.zl.d31.document.po.DocumentChunkIndexPO;
import com.zl.d31.document.po.DocumentVectorIndexPO;
import com.zl.d31.document.vectorindex.config.VectorIndexProperties;
import com.zl.d31.document.vectorindex.dto.ReconciliationReportView;
import com.zl.d31.document.vectorindex.model.VectorIndexStatus;
import com.zl.d31.document.vectorstore.PgVectorStoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨系统一致性核对与补偿服务。
 *
 * <p>以真实数据库为唯一事实来源，核对 MySQL（原文/状态库）与 PG（向量库）：
 * 孤儿向量、缺失向量、卡住任务、数量不一致。dryRun 只出报告；
 * 非 dryRun 执行补偿删除（孤儿）与幂等重建（缺失/卡住），不 Mock 数据库。</p>
 *
 * <p>不引入分布式事务：补偿动作复用 {@link DocumentVectorIndexService} 的
 * 稳定 ID 与 Upsert，保证重复执行安全。</p>
 */
@Slf4j
@Service
public class VectorReconciliationService {

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final PgVectorStoreProperties pgProperties;

    private final DocumentVectorIndexMapper indexMapper;

    private final DocumentChunkIndexMapper chunkMapper;

    private final DocumentVectorIndexService indexService;

    private final VectorIndexProperties properties;

    private final ObjectMapper objectMapper;

    public VectorReconciliationService(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
            PgVectorStoreProperties pgProperties,
            DocumentVectorIndexMapper indexMapper,
            DocumentChunkIndexMapper chunkMapper,
            DocumentVectorIndexService indexService,
            VectorIndexProperties properties,
            ObjectMapper objectMapper) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.pgProperties = pgProperties;
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
        this.indexService = indexService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行核对。
     *
     * @param dryRun true 只出报告；false 执行补偿
     */
    public ReconciliationReportView reconcile(boolean dryRun) {
        ReconciliationReportView report = new ReconciliationReportView();
        report.setDryRun(dryRun);

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
        for (PgRow row : pgRows) {
            pgIds.add(row.id());
            String documentId = String.valueOf(row.metadata().get("documentId"));
            Integer version = parseVersion(row.metadata().get("version"));
            DocumentChunkIndexPO relation = relationByChunkId.get(row.id());
            DocumentVectorIndexPO task = taskByKey.get(taskKey(documentId, version));
            if (relation == null || task == null || !isActive(task.getStatus())) {
                report.getOrphanVectors().add(new ReconciliationReportView.Issue(
                        row.id(), documentId, version,
                        "PG 向量无有效关联或所属任务非活动状态"));
                orphanIds.add(row.id());
            }
        }

        // 2) 缺失向量：关联行存在但 PG 无对应行
        Map<String, List<DocumentChunkIndexPO>> missingByVersion = new HashMap<>();
        for (DocumentChunkIndexPO po : allRelations) {
            if (!pgIds.contains(po.getChunkId())) {
                report.getMissingVectors().add(new ReconciliationReportView.Issue(
                        po.getChunkId(), po.getDocumentId(), po.getVersion(),
                        "关联行存在但 PG 缺失向量"));
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
                        "关联数=%d 而 PG 数=%d".formatted(entry.getValue().size(), pgCount)));
            }
        }

        // 4) 卡住任务
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(properties.getStuckMinutes());
        for (DocumentVectorIndexPO po : allTasks) {
            if ((VectorIndexStatus.PENDING.name().equals(po.getStatus())
                    || VectorIndexStatus.INDEXING.name().equals(po.getStatus()))
                    && po.getUpdatedAt() != null && po.getUpdatedAt().isBefore(cutoff)) {
                report.getStuckTasks().add(new ReconciliationReportView.Issue(
                        taskKey(po.getDocumentId(), po.getVersion()),
                        po.getDocumentId(), po.getVersion(),
                        "任务停留在 " + po.getStatus() + " 超过 " + properties.getStuckMinutes() + " 分钟"));
            }
        }

        if (!dryRun) {
            applyCompensation(report, orphanIds, missingByVersion);
        }
        log.info("核对完成 dryRun={} issues={} (orphan={}, missing={}, mismatch={}, stuck={})",
                dryRun, report.totalIssues(),
                report.getOrphanVectors().size(), report.getMissingVectors().size(),
                report.getCountMismatches().size(), report.getStuckTasks().size());
        return report;
    }

    private void applyCompensation(ReconciliationReportView report,
                                   List<String> orphanIds,
                                   Map<String, List<DocumentChunkIndexPO>> missingByVersion) {
        // 补偿删除孤儿向量
        int batchSize = Math.max(1, properties.getBatchSize());
        for (int from = 0; from < orphanIds.size(); from += batchSize) {
            int to = Math.min(from + batchSize, orphanIds.size());
            indexService.deleteVectorsByIds(orphanIds.subList(from, to));
        }
        if (!orphanIds.isEmpty()) {
            report.getActionsTaken().add("删除孤儿向量 " + orphanIds.size() + " 条");
        }

        // 补偿重建缺失向量：按版本用 Outbox 快照幂等重跑
        for (Map.Entry<String, List<DocumentChunkIndexPO>> entry : missingByVersion.entrySet()) {
            List<DocumentChunkIndexPO> rows = entry.getValue();
            String documentId = rows.get(0).getDocumentId();
            Integer version = rows.get(0).getVersion();
            List<Chunk> chunks = new ArrayList<>(rows.size());
            List<DocumentChunkIndexPO> fullSnapshot =
                    chunkMapper.selectByDocumentVersion(documentId, version);
            for (DocumentChunkIndexPO po : fullSnapshot) {
                chunks.add(toChunk(po));
            }
            indexService.indexChunks(documentId, version,
                    rows.get(0).getStrategy(), null, chunks);
            report.getActionsTaken().add(
                    "重建缺失向量 documentId=%s version=%d".formatted(documentId, version));
        }

        // 卡住任务恢复
        List<com.zl.d31.document.vectorindex.dto.VectorIndexResultView> recovered =
                indexService.recoverStuckTasks();
        if (!recovered.isEmpty()) {
            report.getActionsTaken().add("恢复卡住任务 " + recovered.size() + " 个");
        }
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
        chunk.setStrategy(com.zl.d31.document.chunk.model.ChunkStrategy.valueOf(po.getStrategy()));
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
