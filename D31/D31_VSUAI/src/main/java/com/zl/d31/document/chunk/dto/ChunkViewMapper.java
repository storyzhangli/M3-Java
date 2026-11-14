package com.zl.d31.document.chunk.dto;

import com.zl.d31.document.chunk.dto.ChunkComparisonView;
import com.zl.d31.document.chunk.dto.ChunkSourceView;
import com.zl.d31.document.chunk.dto.ChunkStatsView;
import com.zl.d31.document.chunk.dto.ChunkView;
import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.chunk.model.ChunkComparison;
import com.zl.d31.document.chunk.model.ChunkSource;
import com.zl.d31.document.chunk.model.ChunkStats;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chunk 视图映射器：领域 Chunk/统计/对比 → 对外视图，Controller 不直接暴露领域对象。
 */
@Component
public class ChunkViewMapper {

    public List<ChunkView> toChunkViews(List<Chunk> chunks) {
        return chunks.stream().map(this::toChunkView).toList();
    }

    public ChunkView toChunkView(Chunk chunk) {
        ChunkView view = new ChunkView();
        view.setDocumentId(chunk.getDocumentId());
        view.setTitle(chunk.getTitle());
        view.setVersion(chunk.getVersion());
        view.setStrategy(chunk.getStrategy().name());
        view.setChunkIndex(chunk.getChunkIndex());
        view.setContent(chunk.getContent());
        view.setLength(chunk.getLength());
        view.setCodePointCount(chunk.getCodePointCount());
        view.setSources(chunk.getSources().stream().map(this::toSourceView).toList());
        view.setPageNumbers(chunk.getPageNumbers());
        view.setFirstPageNumber(chunk.getFirstPageNumber());
        view.setLastPageNumber(chunk.getLastPageNumber());
        view.setParagraphIndices(chunk.getParagraphIndices());
        view.setCrossPage(chunk.isCrossPage());
        view.setHasPageLocation(chunk.hasPageLocation());
        view.setHasParagraphLocation(chunk.hasParagraphLocation());
        return view;
    }

    public ChunkComparisonView toComparisonView(ChunkComparison comparison) {
        ChunkComparisonView view = new ChunkComparisonView();
        view.setOriginalLength(comparison.getOriginalLength());
        view.setCleanedLength(comparison.getCleanedLength());
        view.setFixedSizeStats(toStatsView(comparison.getFixedSizeStats()));
        view.setStructureStats(toStatsView(comparison.getStructureStats()));
        return view;
    }

    private ChunkSourceView toSourceView(ChunkSource source) {
        ChunkSourceView view = new ChunkSourceView();
        view.setPageNumber(source.getPageNumber());
        view.setParagraphIndex(source.getParagraphIndex());
        view.setGlobalStart(source.getGlobalStart());
        view.setGlobalEnd(source.getGlobalEnd());
        return view;
    }

    private ChunkStatsView toStatsView(ChunkStats stats) {
        ChunkStatsView view = new ChunkStatsView();
        view.setStrategy(stats.getStrategy().name());
        view.setChunkCount(stats.getChunkCount());
        view.setMinLength(stats.getMinLength());
        view.setMaxLength(stats.getMaxLength());
        view.setAverageLength(stats.getAverageLength());
        view.setEmptyChunkCount(stats.getEmptyChunkCount());
        view.setTotalOverlapChars(stats.getTotalOverlapChars());
        view.setUncoveredChars(stats.getUncoveredChars());
        view.setDuplicatedChars(stats.getDuplicatedChars());
        view.setCrossPageChunkCount(stats.getCrossPageChunkCount());
        view.setChunksWithPageLocation(stats.getChunksWithPageLocation());
        view.setChunksWithParagraphLocation(stats.getChunksWithParagraphLocation());
        view.setMidWordBoundaryCount(stats.getMidWordBoundaryCount());
        view.setLengthDistribution(stats.getLengthDistribution());
        return view;
    }
}
