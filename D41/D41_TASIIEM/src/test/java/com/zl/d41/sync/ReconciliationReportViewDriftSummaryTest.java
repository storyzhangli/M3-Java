package com.zl.d41.sync;

import com.zl.d41.document.vectorindex.dto.ReconciliationReportView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C01/C12：漂移分型计数摘要单测——driftSummary 各计数口径（VECTOR_ONLY 须扣除 STALE 子集）。
 */
class ReconciliationReportViewDriftSummaryTest {

    private static ReconciliationReportView.Issue issue(String key, String category) {
        return new ReconciliationReportView.Issue(key, "d41-doc-a", 1, "detail", category);
    }

    @Test
    @DisplayName("refreshDriftSummary：orphan 中 STALE 子集单列，VECTOR_ONLY=orphan-STALE")
    void vectorOnlySubtractsStale() {
        ReconciliationReportView view = new ReconciliationReportView();
        view.getOrphanVectors().add(issue("c1", com.zl.d41.document.vectorindex.service.VectorReconciliationService.DRIFT_VECTOR_ONLY));
        view.getOrphanVectors().add(issue("c2", com.zl.d41.document.vectorindex.service.VectorReconciliationService.DRIFT_STALE_VERSION));
        view.getOrphanVectors().add(issue("c3", com.zl.d41.document.vectorindex.service.VectorReconciliationService.DRIFT_STALE_VERSION));
        view.getMissingVectors().add(issue("c4", com.zl.d41.document.vectorindex.service.VectorReconciliationService.DRIFT_META_ONLY));
        view.getVersionMismatches().add(issue("c5", com.zl.d41.document.vectorindex.service.VectorReconciliationService.DRIFT_VERSION_MISMATCH));
        view.getSyncStuck().add(issue("c6", com.zl.d41.document.vectorindex.service.VectorReconciliationService.DRIFT_SYNC_STUCK));

        view.refreshDriftSummary();
        ReconciliationReportView.DriftSummary s = view.getDriftSummary();
        assertEquals(1, s.getDriftVectorOnly());
        assertEquals(2, s.getDriftStaleVersion());
        assertEquals(1, s.getDriftMetaOnly());
        assertEquals(1, s.getDriftVersionMismatch());
        assertEquals(1, s.getDriftSyncStuck());
        assertEquals(6, s.getTotal());
    }

    @Test
    @DisplayName("空报告：全计数 0，total=0（修复复查归零断言口径）")
    void emptyReportZeroed() {
        ReconciliationReportView view = new ReconciliationReportView();
        view.refreshDriftSummary();
        assertEquals(0, view.totalIssues());
        assertEquals(0, view.getDriftSummary().getTotal());
        assertEquals(0, view.getDriftSummary().getDriftVectorOnly());
    }

    @Test
    @DisplayName("Issue 兼容：4 参构造 category 默认空串；5 参 null 归一空串")
    void issueConstructorCompat() {
        assertEquals("", new ReconciliationReportView.Issue("k", "d", 1, "x").getCategory());
        assertEquals("", new ReconciliationReportView.Issue("k", "d", 1, "x", null).getCategory());
    }
}
