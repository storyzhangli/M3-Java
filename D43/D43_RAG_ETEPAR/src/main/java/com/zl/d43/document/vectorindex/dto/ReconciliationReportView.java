package com.zl.d43.document.vectorindex.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨系统一致性核对报告视图。
 *
 * <p>所有差异均区分类型并给出可执行补偿动作；dryRun 时 actionsTaken 为空。</p>
 *
 * <p><b>P4 追加</b>：versionMismatches / syncStuck 两类新漂移、Issue.category 显式标类
 * （DRIFT_META_ONLY/DRIFT_VECTOR_ONLY/DRIFT_STALE_VERSION/DRIFT_VERSION_MISMATCH/
 * DRIFT_SYNC_STUCK）、scopePrefix 过滤域、driftSummary 分型计数（受类型 record，
 * 非无约束 Map）。既有字段与构造器签名不动（响应契约向后兼容，H1）。</p>
 */
@Data
public class ReconciliationReportView {

    /** 是否仅演练。 */
    private boolean dryRun;

    /** 核对范围：PG 向量表总行数。 */
    private long pgTotalRows;

    /** 孤儿向量：PG 存在但无有效关联/活动索引（按 category 细分 VECTOR_ONLY/STALE_VERSION）。 */
    private List<Issue> orphanVectors = new ArrayList<>();

    /** 缺失向量：关联行存在但 PG 无对应行（category=DRIFT_META_ONLY）。 */
    private List<Issue> missingVectors = new ArrayList<>();

    /** 卡住任务：长时间停留在 PENDING/INDEXING。 */
    private List<Issue> stuckTasks = new ArrayList<>();

    /** 数量不一致：文档版本级关联数与 PG 数不符。 */
    private List<Issue> countMismatches = new ArrayList<>();

    /** 新增：文档元数据版本 ≠ 活动 COMPLETED 版本，或 PG metadata.version ≠ 关联行 version。 */
    private List<Issue> versionMismatches = new ArrayList<>();

    /** 新增：sync FAILED 到期/耗尽、PENDING 滞留超 stuckMinutes。 */
    private List<Issue> syncStuck = new ArrayList<>();

    /** 新增：表格记录孤儿（所属文档行不存在，或 version ≠ 活动 COMPLETED 版本；补偿=标记 ORPHANED）。 */
    private List<Issue> tableOrphans = new ArrayList<>();

    /** 实际执行的补偿动作（dryRun 时为空）。 */
    private List<String> actionsTaken = new ArrayList<>();

    /** 过滤域（d43- 前缀等；空=全局，LEGACY 行如实列出但由 scope 说明归零断言分母）。 */
    private String scopePrefix = "";

    /** 分型计数摘要（修复复查归零断言的可机读载体）。 */
    private DriftSummary driftSummary = new DriftSummary();

    public int totalIssues() {
        return orphanVectors.size() + missingVectors.size()
                + stuckTasks.size() + countMismatches.size()
                + versionMismatches.size() + syncStuck.size() + tableOrphans.size();
    }

    /** 依据当前列表刷新 driftSummary 计数（调用方在全部填充后执行一次）。 */
    public void refreshDriftSummary() {
        driftSummary.setDriftMetaOnly(missingVectors.size());
        long stale = orphanVectors.stream()
                .filter(i -> "DRIFT_STALE_VERSION".equals(i.getCategory())).count();
        driftSummary.setDriftVectorOnly(orphanVectors.size() - (int) stale);
        driftSummary.setDriftStaleVersion((int) stale);
        driftSummary.setDriftVersionMismatch(versionMismatches.size());
        driftSummary.setDriftSyncStuck(syncStuck.size());
        driftSummary.setDriftTableOrphan(tableOrphans.size());
        driftSummary.setCountMismatch(countMismatches.size());
        driftSummary.setStuckTask(stuckTasks.size());
        driftSummary.setTotal(totalIssues());
    }

    /** 分型计数（强类型，禁无约束 Map 契约）。 */
    @Data
    public static class DriftSummary {
        private int driftMetaOnly;
        private int driftVectorOnly;
        private int driftStaleVersion;
        private int driftVersionMismatch;
        private int driftSyncStuck;
        private int driftTableOrphan;
        private int countMismatch;
        private int stuckTask;
        private int total;
    }

    /**
     * 单条差异项。
     */
    @Data
    public static class Issue {

        /** 关联的稳定 Chunk ID（向量问题）或任务键。 */
        private String key;

        /** 文档标识。 */
        private String documentId;

        /** 文档版本（可空）。 */
        private Integer version;

        /** 差异说明/期望口径。 */
        private String detail;

        /** 追加：显式漂移标类；既有 4-参构造默认按调用点显式设置，空=未标类（兼容）。 */
        private String category = "";

        public Issue() {
        }

        public Issue(String key, String documentId, Integer version, String detail) {
            this.key = key;
            this.documentId = documentId;
            this.version = version;
            this.detail = detail;
        }

        public Issue(String key, String documentId, Integer version, String detail, String category) {
            this(key, documentId, version, detail);
            this.category = category == null ? "" : category;
        }
    }
}
