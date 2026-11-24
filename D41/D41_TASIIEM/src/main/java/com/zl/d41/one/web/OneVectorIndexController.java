package com.zl.d41.one.web;

import com.zl.d41.common.Result;
import com.zl.d41.document.chunk.model.Chunk;
import com.zl.d41.document.chunk.model.ChunkStrategy;
import com.zl.d41.one.index.OneDocumentVectorIndexService;
import com.zl.d41.one.index.OneVectorReconciliationService;
import com.zl.d41.one.index.dto.OneReconciliationReportView;
import com.zl.d41.one.index.dto.OneVectorIndexRequest;
import com.zl.d41.one.index.dto.OneVectorIndexResultView;
import com.zl.d41.one.index.po.OneDocumentChunkIndexPO;
import com.zl.d41.one.index.po.OneDocumentVectorIndexPO;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * one_ 轨向量索引编排入口（阿里百炼远程 Embedding，独立于 local 轨端点）。
 *
 * <p>仅在 app.one.embedding.enabled=true 时装配；统一返回 {@link Result}；
 * 写操作基于稳定 ID 与数据库约束，重复调用安全。无鉴权，仅本地开发/学习用途。</p>
 */
@RestController
@RequestMapping("/api/v1/one/vector-index")
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneVectorIndexController {

    private final OneDocumentVectorIndexService indexService;
    private final OneVectorReconciliationService reconciliationService;

    public OneVectorIndexController(OneDocumentVectorIndexService indexService,
                                    OneVectorReconciliationService reconciliationService) {
        this.indexService = indexService;
        this.reconciliationService = reconciliationService;
    }

    /** 直接 Chunk 幂等入库（one_ 轨，真实远程 Embedding）。 */
    @PostMapping("/index")
    public Result<OneVectorIndexResultView> index(@Valid @RequestBody OneVectorIndexRequest request) {
        List<Chunk> chunks = toChunks(request);
        return Result.success(indexService.indexChunks(request.getDocumentId(),
                request.getVersion(), request.getStrategy(), request.getTitle(), chunks));
    }

    /** 按文档删除全部版本 one_ 向量与关联。 */
    @DeleteMapping("/documents/{documentId}")
    public Result<Integer> delete(@PathVariable String documentId) {
        int before = indexService.listChunks(documentId).size();
        indexService.deleteByDocument(documentId);
        return Result.success(before);
    }

    /** 文档索引状态（全部版本，one_ 表）。 */
    @GetMapping("/documents/{documentId}/status")
    public Result<List<OneDocumentVectorIndexPO>> status(@PathVariable String documentId) {
        return Result.success(indexService.getStatus(documentId));
    }

    /** 文档 Chunk 关联明细（one_ 表）。 */
    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<OneDocumentChunkIndexPO>> chunks(@PathVariable String documentId) {
        return Result.success(indexService.listChunks(documentId));
    }

    /** one_ 表对跨系统一致性核对；默认 dryRun=true 只报告。 */
    @PostMapping("/reconcile")
    public Result<OneReconciliationReportView> reconcile(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return Result.success(reconciliationService.reconcile(dryRun));
    }

    private List<Chunk> toChunks(OneVectorIndexRequest request) {
        List<Chunk> chunks = new ArrayList<>(request.getChunks().size());
        ChunkStrategy strategy = request.getStrategy() == null
                ? ChunkStrategy.STRUCTURE
                : ChunkStrategy.valueOf(request.getStrategy());
        for (OneVectorIndexRequest.ChunkPayload payload : request.getChunks()) {
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