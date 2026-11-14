package com.zl.d31.document.vectorindex;

import com.zl.d31.common.Result;
import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.chunk.model.ChunkStrategy;
import com.zl.d31.document.po.DocumentChunkIndexPO;
import com.zl.d31.document.po.DocumentVectorIndexPO;
import com.zl.d31.document.vectorindex.dto.ChunkIndexView;
import com.zl.d31.document.vectorindex.dto.ReconcileRequest;
import com.zl.d31.document.vectorindex.dto.ReconciliationReportView;
import com.zl.d31.document.vectorindex.dto.VectorIndexRequest;
import com.zl.d31.document.vectorindex.dto.VectorIndexResultView;
import com.zl.d31.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d31.document.vectorindex.service.VectorReconciliationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量索引编排入口：幂等入库、版本更新、删除、核对与恢复。
 *
 * <p>统一返回 {@link Result}；所有写操作基于稳定 ID 与数据库约束，
 * 重复调用安全。无鉴权，仅提供本地开发/学习用途。</p>
 */
@RestController
@RequestMapping("/api/v1/vector-index")
public class VectorIndexController {

    private final DocumentVectorIndexService indexService;

    private final VectorReconciliationService reconciliationService;

    public VectorIndexController(DocumentVectorIndexService indexService,
                                 VectorReconciliationService reconciliationService) {
        this.indexService = indexService;
        this.reconciliationService = reconciliationService;
    }

    /**
     * 直接 Chunk 幂等入库（不依赖原文页表）。
     */
    @PostMapping("/index")
    public Result<VectorIndexResultView> index(@Valid @RequestBody VectorIndexRequest request) {
        List<Chunk> chunks = toChunks(request);
        return Result.success(indexService.indexChunks(request.getDocumentId(),
                request.getVersion(), request.getStrategy(), request.getTitle(), chunks));
    }

    /**
     * 从原文页表加载并切分后入库。
     */
    @PostMapping("/documents/{documentId}/index")
    public Result<VectorIndexResultView> indexFromPages(@PathVariable String documentId,
                                                        @RequestParam(required = false) String strategy) {
        return Result.success(indexService.indexFromPages(documentId, strategy));
    }

    /**
     * 按文档删除全部版本向量与关联。
     */
    @DeleteMapping("/documents/{documentId}")
    public Result<Integer> delete(@PathVariable String documentId) {
        int before = indexService.listChunks(documentId).size();
        indexService.deleteByDocument(documentId);
        return Result.success(before);
    }

    /**
     * 跨系统一致性核对：默认 dryRun。
     */
    @PostMapping("/reconcile")
    public Result<ReconciliationReportView> reconcile(@Valid @RequestBody ReconcileRequest request) {
        return Result.success(reconciliationService.reconcile(Boolean.TRUE.equals(request.getDryRun())));
    }

    /**
     * 手动触发卡住任务恢复。
     */
    @PostMapping("/recover")
    public Result<List<VectorIndexResultView>> recover() {
        return Result.success(indexService.recoverStuckTasks());
    }

    /**
     * 文档索引状态（全部版本）。
     */
    @GetMapping("/documents/{documentId}/status")
    public Result<List<DocumentVectorIndexPO>> status(@PathVariable String documentId) {
        return Result.success(indexService.getStatus(documentId));
    }

    /**
     * 文档 Chunk 关联明细。
     */
    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<ChunkIndexView>> chunks(@PathVariable String documentId) {
        List<ChunkIndexView> views = new ArrayList<>();
        for (DocumentChunkIndexPO po : indexService.listChunks(documentId)) {
            views.add(ChunkIndexView.from(po));
        }
        return Result.success(views);
    }

    private List<Chunk> toChunks(VectorIndexRequest request) {
        List<Chunk> chunks = new ArrayList<>(request.getChunks().size());
        ChunkStrategy strategy = request.getStrategy() == null
                ? ChunkStrategy.STRUCTURE
                : ChunkStrategy.valueOf(request.getStrategy());
        for (VectorIndexRequest.ChunkPayload payload : request.getChunks()) {
            Chunk chunk = new Chunk();
            chunk.setDocumentId(request.getDocumentId());
            chunk.setTitle(request.getTitle());
            chunk.setVersion(request.getVersion() == null ? 1 : request.getVersion());
            chunk.setStrategy(strategy);
            chunk.setChunkIndex(payload.getChunkIndex());
            chunk.setContent(payload.getContent());
            chunk.setLength(payload.getContent().length());
            chunk.setGlobalStart(payload.getGlobalStart());
            chunk.setGlobalEnd(payload.getGlobalEnd());
            chunks.add(chunk);
        }
        return chunks;
    }
}
