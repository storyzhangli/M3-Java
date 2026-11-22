package com.zl.d39.document.embedding.controller;

import com.zl.d39.access.AccessPolicyService;
import com.zl.d39.common.Result;
import com.zl.d39.document.chunk.model.Chunk;
import com.zl.d39.document.chunk.model.ChunkResult;
import com.zl.d39.document.chunk.model.ChunkStrategy;
import com.zl.d39.document.chunk.service.DocumentChunkService;
import com.zl.d39.document.embedding.dto.*;
import com.zl.d39.document.embedding.model.CompatibilityDecision;
import com.zl.d39.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d39.document.embedding.model.EmbeddingRecord;
import com.zl.d39.document.embedding.service.DocumentEmbeddingService;
import com.zl.d39.document.embedding.service.VectorIndexCompatibilityService;
import com.zl.d39.document.service.DocumentPageService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Embedding 入口：模型元数据、文档向量化与索引兼容性。
 *
 * <p>HTTP 统一返回 {@link Result}，业务结果通过 code 区分；不实现检索/问答，
 * 仅负责把 Chunk 文本转为可追溯向量并校验索引兼容性。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class EmbeddingController {

    private final DocumentPageService pageService;

    private final DocumentChunkService chunkService;

    private final DocumentEmbeddingService embeddingService;

    private final VectorIndexCompatibilityService compatibilityService;

    private final EmbeddingViewMapper mapper;

    /** D39 P3：embed 端点带 documentId 暴露向量内容 → DOC_SCOPE_CHECK（C18）；disabled 零差异（H1）。 */
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    public EmbeddingController(DocumentPageService pageService,
                               DocumentChunkService chunkService,
                               DocumentEmbeddingService embeddingService,
                               VectorIndexCompatibilityService compatibilityService,
                               EmbeddingViewMapper mapper,
                               ObjectProvider<AccessPolicyService> accessPolicyProvider) {
        this.pageService = pageService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.compatibilityService = compatibilityService;
        this.mapper = mapper;
        this.accessPolicyProvider = accessPolicyProvider;
    }

    /**
     * 当前模型与向量空间元数据。
     */
    @GetMapping("/embeddings/model")
    public Result<EmbeddingModelMetadataView> model() {
        return Result.success(mapper.toMetadataView(compatibilityService.currentIndex()));
    }

    /**
     * 对指定文档的 Chunk 真实向量化（默认固定长度策略）。
     */
    @PostMapping("/documents/{documentId}/embeddings")
    public Result<List<EmbeddingRecordView>> embed(
            @PathVariable("documentId") String documentId,
            @RequestParam(value = "strategy", defaultValue = "FIXED_SIZE") ChunkStrategy strategy) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy != null) {
            policy.requireDocument(policy.requireCurrent(), documentId);
        }
        ChunkResult chunkResult = chunkService.process(pageService.listPages(documentId));
        List<Chunk> chunks = strategy == ChunkStrategy.STRUCTURE
                ? chunkResult.getStructureChunks()
                : chunkResult.getFixedChunks();
        List<EmbeddingRecord> records = embeddingService.embedChunks(chunks);
        return Result.success(mapper.toRecordViews(records));
    }

    /**
     * 索引兼容性检查：异维度/同维度异模型均 fail-closed。
     */
    @PostMapping("/embeddings/index/compatibility")
    public Result<CompatibilityDecisionView> checkCompatibility(
            @Valid @RequestBody CompatibilityCheckRequest request) {
        EmbeddingModelMetadata candidate = mapper.toCandidate(request);
        CompatibilityDecision decision = compatibilityService.check(candidate);
        return Result.success(mapper.toDecisionView(
                decision, compatibilityService.currentIndex(), candidate));
    }
}
