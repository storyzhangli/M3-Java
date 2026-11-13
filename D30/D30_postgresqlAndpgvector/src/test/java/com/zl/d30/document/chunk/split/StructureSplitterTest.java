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
 * 标题/段落结构切分测试：真实文本样本，断言边界、合并、二次切分与来源。
 */
class StructureSplitterTest {

    private final StructureSplitter splitter =
            new StructureSplitter(new ChunkBoundarySupport());
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
    void headingStartsNewChunkAndStaysWithContent() {
        ChunkProperties props = ChunkTestSamples.properties();
        String content = "# Heading One\nparagraph alpha text\n# Heading Two\nparagraph beta text";
        List<Chunk> chunks = split(content, props);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getContent()).contains("# Heading One", "paragraph alpha");
        assertThat(chunks.get(1).getContent()).contains("# Heading Two", "paragraph beta");
    }

    @Test
    void shortParagraphsAreMergedIntoOneChunk() {
        ChunkProperties props = ChunkTestSamples.properties();
        String content = "First short line.\nSecond short line.\nThird short line.";
        List<Chunk> chunks = split(content, props);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent())
                .contains("First short line.", "Second short line.", "Third short line.");
    }

    @Test
    void oversizedBlockIsSecondSplitAndBounded() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.unbroken(1000), props);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.getLength()).isLessThanOrEqualTo(props.getMaxChunkSize());
            assertThat(c.getContent()).isNotEmpty();
        });
        assertThat(chunks.get(chunks.size() - 1).getLength()).isEqualTo(200);
    }

    @Test
    void veryShortLastBlockIsRetained() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split("First paragraph with some reasonable length here.\nx", props);

        Chunk last = chunks.get(chunks.size() - 1);
        assertThat(last.getContent()).contains("x");
        assertThat(last.getContent()).isNotEmpty();
    }

    @Test
    void listItemsRemainIntact() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split("# Section\n- item one\n- item two", props);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent()).contains("- item one", "- item two");
    }

    @Test
    void allParagraphIndicesArePreserved() {
        ChunkProperties props = ChunkTestSamples.properties();
        CleanedDocument document = cleaned(
                "First paragraph line.\nSecond paragraph line.\nThird paragraph line.\nFourth paragraph line.");
        List<Chunk> chunks = splitter.split(document, props, context());

        List<Integer> indices = chunks.stream()
                .flatMap(c -> c.getParagraphIndices().stream())
                .distinct().sorted().toList();
        int blockCount = document.getBlocks().size();
        assertThat(indices).hasSize(blockCount);
        assertThat(indices.get(0)).isZero();
        assertThat(indices.get(blockCount - 1)).isEqualTo(blockCount - 1);
    }

    @Test
    void crossPageSourcesArePreserved() {
        ChunkProperties props = ChunkTestSamples.properties();
        CleanedDocument document = cleaner.clean(pages("First page short text.", "Second page short text."));
        List<Chunk> chunks = splitter.split(document, props, context());

        Chunk chunk = chunks.get(0);
        assertThat(chunk.isCrossPage()).isTrue();
        assertThat(chunk.getPageNumbers()).containsExactly(1, 2);
    }

    @Test
    void degradesWithoutAnyHeading() {
        ChunkProperties props = ChunkTestSamples.properties();
        List<Chunk> chunks = split(ChunkTestSamples.repeatedWords(120), props);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.getLength()).isLessThanOrEqualTo(props.getMaxChunkSize()));
    }
}
