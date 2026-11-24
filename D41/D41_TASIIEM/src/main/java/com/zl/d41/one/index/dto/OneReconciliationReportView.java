package com.zl.d41.one.index.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * one_ 轨跨系统一致性核对报告（typed 契约，禁用无约束 Map）。
 *
 * <p>按表独立核对：one_d41_vector_store（PG）vs one_document_chunk_index（MySQL），
 * 不得跨轨 JOIN 计数（H3 向量空间隔离）。</p>
 */
@Data
public class OneReconciliationReportView {

    /** 是否仅报告不补偿。 */
    private boolean dryRun;

    /** 核对的文档版本条目。 */
    private List<Entry> entries = new ArrayList<>();

    /** PG 孤儿向量总数（MySQL 无关联）。 */
    private int orphanPgTotal;

    /** PG 缺失向量总数（MySQL 有关联）。 */
    private int missingPgTotal;

    /** 实际补偿删除的 PG 孤儿向量数（dryRun=true 恒 0）。 */
    private int orphanDeleted;

    @Data
    public static class Entry {
        private String documentId;
        private Integer version;
        private String status;
        private long pgCount;
        private int relationCount;
        private List<String> orphanPgIds = new ArrayList<>();
        private List<String> missingPgIds = new ArrayList<>();
        private boolean consistent;
    }
}