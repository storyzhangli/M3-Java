package com.zl.d40.document.sync.service;

import com.zl.d40.document.sync.dto.SyncRetryResultView;
import com.zl.d40.document.sync.model.SyncOperation;
import com.zl.d40.document.sync.po.DocumentSyncPO;
import com.zl.d40.document.vectorindex.service.DocumentVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * sync 重试编排器：把状态机与真实管线（H4 修复只走既有管线）接起来。
 *
 * <p>依赖方向冻结（防环）：{@code DocumentVectorIndexService → DocumentSyncService}（落状态）；
 * {@code SyncRetryRunner → {DocumentSyncService, DocumentVectorIndexService}}（编排）；
 * 状态机服务本身不依赖管线。</p>
 *
 * <p>管线语义：</p>
 * <ul>
 *   <li>DELETE → {@code deleteByDocument} 全闭环重跑（标记幂等、PG 删除可重复、
 *       双端确认再判 SYNCED；已收敛时短路零写入）；</li>
 *   <li>UPDATE_CLEANUP → {@code retryCleanupVersion} 对目标旧版本重跑清理
 *       （稳定 UUID 与 DB Upsert 保证重入安全）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SyncRetryRunner {

    private final DocumentSyncService syncService;

    private final DocumentVectorIndexService indexService;

    public SyncRetryRunner(DocumentSyncService syncService,
                           DocumentVectorIndexService indexService) {
        this.syncService = syncService;
        this.indexService = indexService;
    }

    /**
     * 自动重试（调度入口）：FAILED ∧ 到期 ∧ attempt&lt;max → claim(非手动) → 管线重跑。
     */
    public List<SyncRetryResultView> retryDue() {
        List<SyncRetryResultView> results = new ArrayList<>();
        for (DocumentSyncPO due : syncService.findDueForRetry()) {
            DocumentSyncPO claimed = syncService.claimForRetry(due, false);
            if (claimed != null) {
                results.add(executePipeline(claimed));
            }
        }
        return results;
    }

    /**
     * 手动重试（ADMIN 端点，恒可用 H2）：FAILED 行（含耗尽，claim 时 attempt 重置）→ 管线重跑。
     *
     * @param documentId 可空=全部 FAILED 行
     */
    public List<SyncRetryResultView> retryManual(String documentId) {
        List<SyncRetryResultView> results = new ArrayList<>();
        for (DocumentSyncPO row : syncService.findFailedForManualRetry(documentId)) {
            DocumentSyncPO claimed = syncService.claimForRetry(row, true);
            if (claimed != null) {
                results.add(executePipeline(claimed));
            }
        }
        return results;
    }

    /** 单行管线执行：异常已由闭环落 FAILED（这里只兜底捕获编排层异常并如实呈现）。 */
    private SyncRetryResultView executePipeline(DocumentSyncPO claimed) {
        SyncRetryResultView view = new SyncRetryResultView();
        view.setDocumentId(claimed.getDocumentId());
        view.setOperation(claimed.getOperation());
        view.setVersion(claimed.getVersion());
        SyncOperation operation = SyncOperation.fromName(claimed.getOperation());
        try {
            if (operation == SyncOperation.DELETE) {
                indexService.deleteByDocument(claimed.getDocumentId());
            } else if (operation == SyncOperation.UPDATE_CLEANUP) {
                indexService.retryCleanupVersion(claimed.getDocumentId(), claimed.getVersion());
            } else {
                view.setPipelineCompleted(false);
                view.setDetail("未知 operation，拒绝执行（fail-closed）");
                return view;
            }
            view.setPipelineCompleted(true);
        } catch (Exception ex) {
            // 闭环内已 markFailed 落库并改写状态；此处仅记录编排层视图，异常不外溢（列表继续执行）
            view.setPipelineCompleted(false);
            view.setDetail(safeSummary(ex));
        }
        DocumentSyncPO after = syncService.find(claimed.getDocumentId(), operation, claimed.getVersion());
        view.setSyncStatus(after == null ? "MISSING" : after.getSyncStatus());
        view.setReasonCode(after == null ? "" : after.getReasonCode());
        view.setAttemptCount(after == null ? -1 : after.getAttemptCount());
        return view;
    }

    private static String safeSummary(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
