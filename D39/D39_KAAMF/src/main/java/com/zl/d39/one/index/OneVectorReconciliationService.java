package com.zl.d39.one.index;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d39.one.index.dto.OneReconciliationReportView;
import com.zl.d39.one.index.mapper.OneDocumentChunkIndexMapper;
import com.zl.d39.one.index.mapper.OneDocumentVectorIndexMapper;
import com.zl.d39.one.index.po.OneDocumentChunkIndexPO;
import com.zl.d39.one.index.po.OneDocumentVectorIndexPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * one_ 轨跨系统一致性核对与补偿（只核对 one_ 表对，不跨轨）。
 *
 * <p>对每个 MySQL 索引版本行：PG id 集合 vs MySQL 关联 chunkId 集合。
 * PG 多出 = 孤儿向量（dryRun=false 时按稳定 ID 补偿删除）；
 * PG 缺少 = 缺失向量（只报告，由重新 index 收敛，禁止凭空补写）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneVectorReconciliationService {

    private final OneDocumentVectorIndexService indexService;
    private final OneDocumentVectorIndexMapper indexMapper;
    private final OneDocumentChunkIndexMapper chunkMapper;

    public OneVectorReconciliationService(OneDocumentVectorIndexService indexService,
                                          OneDocumentVectorIndexMapper indexMapper,
                                          OneDocumentChunkIndexMapper chunkMapper) {
        this.indexService = indexService;
        this.indexMapper = indexMapper;
        this.chunkMapper = chunkMapper;
    }

    /** 全量核对；dryRun=true 只报告不补偿。 */
    public OneReconciliationReportView reconcile(boolean dryRun) {
        OneReconciliationReportView report = new OneReconciliationReportView();
        report.setDryRun(dryRun);

        List<OneDocumentVectorIndexPO> tasks = indexMapper.selectList(
                new QueryWrapper<OneDocumentVectorIndexPO>().orderByAsc("document_id", "version"));
        for (OneDocumentVectorIndexPO task : tasks) {
            OneReconciliationReportView.Entry entry = new OneReconciliationReportView.Entry();
            entry.setDocumentId(task.getDocumentId());
            entry.setVersion(task.getVersion());
            entry.setStatus(task.getStatus());

            Set<String> pgIds = new LinkedHashSet<>(
                    indexService.listPgIdsForVersion(task.getDocumentId(), task.getVersion()));
            List<OneDocumentChunkIndexPO> rows = chunkMapper.selectByDocumentVersion(
                    task.getDocumentId(), task.getVersion());
            Set<String> relationIds = new LinkedHashSet<>();
            for (OneDocumentChunkIndexPO row : rows) {
                relationIds.add(row.getChunkId());
            }
            entry.setPgCount(pgIds.size());
            entry.setRelationCount(relationIds.size());

            List<String> orphan = new ArrayList<>();
            for (String pgId : pgIds) {
                if (!relationIds.contains(pgId)) {
                    orphan.add(pgId);
                }
            }
            List<String> missing = new ArrayList<>();
            for (String relationId : relationIds) {
                if (!pgIds.contains(relationId)) {
                    missing.add(relationId);
                }
            }
            entry.setOrphanPgIds(orphan);
            entry.setMissingPgIds(missing);
            entry.setConsistent(orphan.isEmpty() && missing.isEmpty());
            report.getEntries().add(entry);
            report.setOrphanPgTotal(report.getOrphanPgTotal() + orphan.size());
            report.setMissingPgTotal(report.getMissingPgTotal() + missing.size());

            if (!dryRun && !orphan.isEmpty()) {
                indexService.deleteVectorsByIds(orphan);
                report.setOrphanDeleted(report.getOrphanDeleted() + orphan.size());
                log.info("one_ 核对补偿删除孤儿向量 documentId={} version={} count={}",
                        task.getDocumentId(), task.getVersion(), orphan.size());
            }
        }
        log.info("one_ 核对完成 dryRun={} entries={} orphan={} missing={} deleted={}",
                dryRun, report.getEntries().size(), report.getOrphanPgTotal(),
                report.getMissingPgTotal(), report.getOrphanDeleted());
        return report;
    }
}