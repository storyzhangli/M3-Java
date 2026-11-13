package com.zl.d30.document.chunk.split;

import com.zl.d30.document.chunk.ChunkTestSamples;
import com.zl.d30.document.chunk.clean.TextCleaner;
import com.zl.d30.document.chunk.config.ChunkProperties;
import com.zl.d30.document.chunk.model.Chunk;
import com.zl.d30.document.chunk.model.ChunkContext;
import com.zl.d30.document.chunk.model.CleanedDocument;
import com.zl.d30.document.chunk.support.ChunkBoundarySupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d30.document.chunk.ChunkTestSamples.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定长度切分测试：真实文本样本，断言数量、长度边界、Overlap、来源与空块。
 */
class FixedSizeSplitterTest {

    private final FixedSizeSplitter splitter =
            new FixedSizeSplitter(new ChunkBoundarySupport());
    private final TextCleaner cleaner = new TextCleaner();

    private CleanedDocument cleaned(String... contents) {
        return cleaner.clean(pages(contents));
    }

    private ChunkContext context() {
        return new ChunkContext(DOCUMENT_ID, TITLE, 1);
    }

    private List<Chunk> split(String content, ChunkProperties props) {
        return splitter.split(cleaned(content), props, context());
    }

    @Test
    void chunksRespectMaxSizeAndSequentialIndex() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.repeatedWords(80), props);

        assertThat(chunks).size().isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).getChunkIndex()).isEqualTo(i);
            assertThat(chunks.get(i).getLength()).isLessThanOrEqualTo(props.getChunkSize());
        }
    }

    @Test
    void noEmptyChunks() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.repeatedWords(60), props);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.getContent()).isNotEmpty();
            assertThat(c.getLength()).isGreaterThan(0);
        });
    }

    @Test
    void emptyInputReturnsEmptyList() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = splitter.split(cleaned(""), props, context());
        assertThat(chunks).isEmpty();
    }

    @Test
    void overlapZeroProducesAdjacentNonOverlappingChunks() {
        ChunkProperties props = ChunkTestSamples.properties();
        props.setChunkOverlap(0);
        List<Chunk> chunks = split(ChunkTestSamples.repeatedWords(80), props);

        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            int overlap = chunks.get(i - 1).getGlobalEnd() - chunks.get(i).getGlobalStart();
            assertThat(overlap).isZero();
        }
    }

    @Test
    void overlapPositiveStaysWithinConfiguredBound() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.repeatedWords(80), props);

        boolean sawPositiveOverlap = false;
        for (int i = 1; i < chunks.size(); i++) {
            int overlap = chunks.get(i - 1).getGlobalEnd() - chunks.get(i).getGlobalStart();
            // 边界保护使实际重叠落在 [0, 配置值]，硬切点时恰好等于配置值。
            assertThat(overlap).isBetween(0, props.getChunkOverlap());
            sawPositiveOverlap |= overlap > 0;
        }
        assertThat(sawPositiveOverlap).isTrue();
    }

    @Test
    void noChunkShorterThanMinAfterTailMerge() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.repeatedWords(70), props);

        if (chunks.size() > 1) {
            assertThat(chunks).allSatisfy(c ->
                    assertThat(c.getLength()).isGreaterThanOrEqualTo(props.getMinChunkSize()));
        }
        assertThat(chunks.get(chunks.size() - 1).getGlobalEnd())
                .isEqualTo(cleaned(ChunkTestSamples.repeatedWords(70)).getCleanedLength());
    }

    @Test
    void longUnbrokenStringIsHardCut() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.unbroken(1000), props);

        // 硬游标 step=150，无空格只能硬切：6 个 200 块 + 末尾 100 块。
        assertThat(chunks).hasSize(7);
        for (int i = 0; i < 6; i++) {
            assertThat(chunks.get(i).getLength()).isEqualTo(200);
        }
        assertThat(chunks.get(6).getLength()).isEqualTo(100);
    }

    @Test
    void sourceMetadataAndDocumentIdentityAreKept() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.repeatedWords(60), props);

        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.getDocumentId()).isEqualTo(DOCUMENT_ID);
            assertThat(c.getTitle()).isEqualTo(TITLE);
            assertThat(c.getVersion()).isEqualTo(1);
            assertThat(c.getSources()).isNotEmpty();
            assertThat(c.getFirstPageNumber()).isEqualTo(1);
        });
    }

    @Test
    void crossPageSourcesCoverBothPages() {
        ChunkProperties props = ChunkTestSamples.properties();
        CleanedDocument document = cleaner.clean(pages(
                ChunkTestSamples.repeatedWords(40), ChunkTestSamples.repeatedWords(40)));
        List<Chunk> chunks = splitter.split(document, props, context());

        List<Integer> allPages = chunks.stream()
                .flatMap(c -> c.getPageNumbers().stream()).distinct().sorted().toList();
        assertThat(allPages).containsExactly(1, 2);
    }

    @Test
    void neverSplitsSurrogatePair() {
        ChunkProperties props = ChunkTestSamples.properties();
        String content = "start " + "😀".repeat(60) + " end";
        List<Chunk> chunks = split(content, props);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            String text = c.getContent();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (Character.isHighSurrogate(ch)) {
                    assertThat(i + 1).isLessThan(text.length());
                    assertThat(Character.isLowSurrogate(text.charAt(i + 1))).isTrue();
                }
                if (Character.isLowSurrogate(ch)) {
                    assertThat(i).isGreaterThan(0);
                    assertThat(Character.isHighSurrogate(text.charAt(i - 1))).isTrue();
                }
            }
        });
    }

    @Test
    void chineseTextChunksWithinSize() {
        ChunkProperties props = ChunkTestSamples.properties();
        String content = "这是中文测试内容".repeat(60);
        List<Chunk> chunks = split(content, props);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.getLength()).isLessThanOrEqualTo(props.getChunkSize()));
    }
}
