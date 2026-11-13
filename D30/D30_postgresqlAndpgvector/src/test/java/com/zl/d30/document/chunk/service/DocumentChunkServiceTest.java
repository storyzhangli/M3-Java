package com.zl.d30.document.chunk.service;

import com.zl.d30.document.chunk.ChunkTestSamples;
import com.zl.d30.document.chunk.clean.TextCleaner;
import com.zl.d30.document.chunk.config.ChunkProperties;
import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.chunk.model.ChunkResult;
import com.zl.d30.document.chunk.model.ChunkStrategy;
import com.zl.d30.document.chunk.split.FixedSizeSplitter;
import com.zl.d30.document.chunk.split.StructureSplitter;
import com.zl.d30.document.chunk.support.ChunkBoundarySupport;
import com.zl.d30.document.model.DocumentPageContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d30.document.chunk.ChunkTestSamples.page;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chunk 编排服务测试：真实页面对象端到端，不使用 Mock 证明切分。
 */
class DocumentChunkServiceTest {

    private DocumentChunkService newService(ChunkProperties props) {
        ChunkBoundarySupport support = new ChunkBoundarySupport();
        return new DocumentChunkService(
                new TextCleaner(),
                new FixedSizeSplitter(support),
                new StructureSplitter(support),
                props);
    }

    @Test
    void bothStrategiesRunWithoutTextLossOrEmptyChunks() {
        DocumentChunkService service = newService(ChunkTestSamples.properties());
        List<DocumentPageContent> pages = List.of(
                page(1, ChunkTestSamples.repeatedWords(60)),
                page(2, ChunkTestSamples.repeatedWords(60)));

        ChunkResult result = service.process(pages);

        assertThat(result.getFixedChunks()).isNotEmpty();
        assertThat(result.getStructureChunks()).isNotEmpty();
        assertThat(result.getComparison().getFixedSizeStats().getUncoveredChars()).isZero();
        assertThat(result.getComparison().getStructureStats().getUncoveredChars()).isZero();
        assertThat(result.getComparison().getFixedSizeStats().getEmptyChunkCount()).isZero();
        assertThat(result.getComparison().getStructureStats().getEmptyChunkCount()).isZero();
    }

    @Test
    void emptyPagesProduceNoChunks() {
        DocumentChunkService service = newService(ChunkTestSamples.properties());
        ChunkResult result = service.process(List.of(page(1, "")));

        assertThat(result.getFixedChunks()).isEmpty();
        assertThat(result.getStructureChunks()).isEmpty();
    }

    @Test
    void chunksOfDifferentVersionsAreDistinguished() {
        DocumentChunkService service = newService(ChunkTestSamples.properties());

        ChunkResult v1 = service.process(List.of(
                page(1, ChunkTestSamples.repeatedWords(30), 1)));
        ChunkResult v2 = service.process(List.of(
                page(1, ChunkTestSamples.repeatedWords(45), 2)));

        assertThat(v1.getFixedChunks().get(0).getVersion()).isEqualTo(1);
        assertThat(v2.getFixedChunks().get(0).getVersion()).isEqualTo(2);
        // 不同版本输入不同，规范全文长度与首块内容可区分，且版本号各自正确。
        assertThat(v1.getCleaned().getCleanedLength())
                .isNotEqualTo(v2.getCleaned().getCleanedLength());
    }

    @Test
    void runStrategyReturnsOnlyRequestedStrategy() {
        DocumentChunkService service = newService(ChunkTestSamples.properties());
        List<DocumentPageContent> pages = List.of(
                page(1, ChunkTestSamples.repeatedWords(60)));

        List<Chunk> fixed = service.runStrategy(pages, ChunkStrategy.FIXED_SIZE);
        List<Chunk> structure = service.runStrategy(pages, ChunkStrategy.STRUCTURE);

        assertThat(fixed).isNotEmpty();
        assertThat(structure).isNotEmpty();
        assertThat(fixed.get(0).getStrategy()).isEqualTo(ChunkStrategy.FIXED_SIZE);
        assertThat(structure.get(0).getStrategy()).isEqualTo(ChunkStrategy.STRUCTURE);
    }

    @Test
    void comparisonRecordsOriginalAndCleanedLength() {
        DocumentChunkService service = newService(ChunkTestSamples.properties());
        List<DocumentPageContent> pages = List.of(
                page(1, "a   b\n\nc"));

        ChunkResult result = service.process(pages);
        assertThat(result.getComparison().getOriginalLength())
                .isEqualTo(pages.get(0).getContent().length());
        assertThat(result.getComparison().getCleanedLength()).isGreaterThan(0);
    }
}
