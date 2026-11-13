package com.zl.d30.document.embedding.controller;

import com.zl.d30.common.Result;
import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.chunk.model.ChunkResult;
import com.zl.d30.document.chunk.model.ChunkStrategy;
import com.zl.d30.document.chunk.service.DocumentChunkService;
import com.zl.d30.document.embedding.dto.CompatibilityCheckRequest;
import com.zl.d30.document.embedding.dto.CompatibilityDecisionView;
import com.zl.d30.document.embedding.dto.EmbeddingModelMetadataView;
import com.zl.d30.document.embedding.dto.EmbeddingRecordView;
import com.zl.d30.document.embedding.dto.EmbeddingViewMapper;
import com.zl.d30.document.embedding.model.CompatibilityDecision;
import com.zl.d30.document.embedding.model.EmbeddingModelMetadata;
import com.zl.d30.document.embedding.model.EmbeddingRecord;
import com.zl.d30.document.embedding.service.DocumentEmbeddingService;
import com.zl.d30.document.embedding.service.VectorIndexCompatibilityService;
import com.zl.d30.document.service.DocumentPageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    public EmbeddingController(DocumentPageService pageService,
                               DocumentChunkService chunkService,
                               DocumentEmbeddingService embeddingService,
                               VectorIndexCompatibilityService compatibilityService,
                               EmbeddingViewMapper mapper) {
        this.pageService = pageService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.compatibilityService = compatibilityService;
        this.mapper = mapper;
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
