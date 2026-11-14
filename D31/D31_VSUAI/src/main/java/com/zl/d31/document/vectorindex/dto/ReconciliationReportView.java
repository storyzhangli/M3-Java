package com.zl.d31.document.vectorindex.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨系统一致性核对报告视图。
 *
 * <p>所有差异均区分类型并给出可执行补偿动作；dryRun 时 actionsTaken 为空。</p>
 */
@Data
public class ReconciliationReportView {

    /** 是否仅演练。 */
    private boolean dryRun;

    /** 核对范围：PG 向量表总行数。 */
    private long pgTotalRows;

    /** 孤儿向量：PG 存在但无有效关联/活动索引。 */
    private List<Issue> orphanVectors = new ArrayList<>();

    /** 缺失向量：关联行存在但 PG 无对应行。 */
    private List<Issue> missingVectors = new ArrayList<>();

    /** 卡住任务：长时间停留在 PENDING/INDEXING。 */
    private List<Issue> stuckTasks = new ArrayList<>();

    /** 数量不一致：文档版本级关联数与 PG 数不符。 */
    private List<Issue> countMismatches = new ArrayList<>();

    /** 实际执行的补偿动作（dryRun 时为空）。 */
    private List<String> actionsTaken = new ArrayList<>();

    public int totalIssues() {
        return orphanVectors.size() + missingVectors.size()
                + stuckTasks.size() + countMismatches.size();
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

        public Issue() {
        }

        public Issue(String key, String documentId, Integer version, String detail) {
            this.key = key;
            this.documentId = documentId;
            this.version = version;
            this.detail = detail;
        }
    }
}
