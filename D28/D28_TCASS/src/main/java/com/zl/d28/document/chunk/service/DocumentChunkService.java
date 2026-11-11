package com.zl.d28.document.chunk.service;

import com.zl.d28.document.chunk.clean.TextCleaner;
import com.zl.d28.document.chunk.config.ChunkProperties;
import com.zl.d28.document.chunk.model.Chunk;
import com.zl.d28.document.chunk.model.ChunkContext;
import com.zl.d28.document.chunk.model.ChunkComparison;
import com.zl.d28.document.chunk.model.ChunkResult;
import com.zl.d28.document.chunk.model.ChunkStrategy;
import com.zl.d28.document.chunk.model.CleanedDocument;
import com.zl.d28.document.chunk.split.FixedSizeSplitter;
import com.zl.d28.document.chunk.split.StructureSplitter;
import com.zl.d28.document.model.DocumentPageContent;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文档 Chunk 编排服务：页面 → 清洗 → 两种策略切分 → 对比。
 *
 * <p>本服务不持久化 Chunk、不依赖 Embedding/VectorStore，保持切分能力独立；
 * 算法正确性由清洗器与切分器的真实文本单测证明，本服务只做编排与上下文组装。</p>
 */
@Service
public class DocumentChunkService {

    private final TextCleaner cleaner;
    private final FixedSizeSplitter fixedSizeSplitter;
    private final StructureSplitter structureSplitter;
    private final ChunkProperties properties;

    public DocumentChunkService(TextCleaner cleaner,
                                FixedSizeSplitter fixedSizeSplitter,
                                StructureSplitter structureSplitter,
                                ChunkProperties properties) {
        this.cleaner = cleaner;
        this.fixedSizeSplitter = fixedSizeSplitter;
        this.structureSplitter = structureSplitter;
        this.properties = properties;
    }

    /**
     * 对页面列表执行完整处理：清洗 + 两策略切分 + 对比。
     */
    public ChunkResult process(List<DocumentPageContent> pages) {
        properties.validate();
        CleanedDocument cleaned = cleaner.clean(pages);
        ChunkContext context = buildContext(pages);

        List<Chunk> fixedChunks = fixedSizeSplitter.split(cleaned, properties, context);
        List<Chunk> structureChunks = structureSplitter.split(cleaned, properties, context);

        ChunkResult result = new ChunkResult();
        result.setCleaned(cleaned);
        result.setFixedChunks(fixedChunks);
        result.setStructureChunks(structureChunks);
        result.setComparison(ChunkComparison.of(
                cleaned.getOriginalLength(), cleaned, fixedChunks, structureChunks));
        return result;
    }

    /**
     * 仅执行指定策略切分。
     */
    public List<Chunk> runStrategy(List<DocumentPageContent> pages, ChunkStrategy strategy) {
        properties.validate();
        CleanedDocument cleaned = cleaner.clean(pages);
        ChunkContext context = buildContext(pages);
        if (strategy == ChunkStrategy.STRUCTURE) {
            return structureSplitter.split(cleaned, properties, context);
        }
        return fixedSizeSplitter.split(cleaned, properties, context);
    }

    private ChunkContext buildContext(List<DocumentPageContent> pages) {
        if (pages == null || pages.isEmpty()) {
            return new ChunkContext("", "", 1);
        }
        DocumentPageContent first = pages.get(0);
        return new ChunkContext(
                first.getDocumentId() == null ? "" : first.getDocumentId(),
                first.getTitle() == null ? "" : first.getTitle(),
                first.getVersion() == null ? 1 : first.getVersion());
    }
}
