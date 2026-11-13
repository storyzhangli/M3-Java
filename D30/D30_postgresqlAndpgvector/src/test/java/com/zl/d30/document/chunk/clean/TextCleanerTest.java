package com.zl.d30.document.chunk.clean;

import com.zl.d30.document.chunk.model.BlockType;
import com.zl.d30.document.chunk.model.CleanedDocument;
import com.zl.d30.document.chunk.model.TextBlock;
import com.zl.d30.document.model.DocumentPageContent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d30.document.chunk.ChunkTestSamples.page;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本清洗组件测试：真实文本样本，断言结构、空白归一与来源定位。
 */
class TextCleanerTest {

    private final TextCleaner cleaner = new TextCleaner();

    @Test
    void normalizesLineEndings() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "a\r\nb\rc")));
        assertThat(doc.getJoinedText()).isEqualTo("a\nb\nc");
        assertThat(doc.getBlocks()).hasSize(3);
    }

    @Test
    void collapsesHorizontalWhitespace() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "a    b\t\tc")));
        assertThat(doc.getBlocks()).hasSize(1);
        assertThat(doc.getBlocks().get(0).getText()).isEqualTo("a b c");
    }

    @Test
    void normalizesAbnormalWhitespaceChars() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "a\u00A0\u00A0b\u3000c")));
        assertThat(doc.getBlocks().get(0).getText()).isEqualTo("a b c");
    }

    @Test
    void collapsesRepeatedBlankLines() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "a\n\n\n\nb")));
        assertThat(doc.getBlocks()).extracting(TextBlock::getText).containsExactly("a", "b");
        assertThat(doc.getJoinedText()).isEqualTo("a\nb");
    }

    @Test
    void emptyTextProducesNoBlocks() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "")));
        assertThat(doc.isEmpty()).isTrue();
        assertThat(doc.getBlocks()).isEmpty();
        assertThat(doc.getJoinedText()).isEmpty();
    }

    @Test
    void whitespaceOnlyIsEmptied() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "   \n\t \n\u00A0 ")));
        assertThat(doc.isEmpty()).isTrue();
    }

    @Test
    void preservesHeadingAndListStructure() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "# Title\n- item one\n- item two")));
        assertThat(doc.getBlocks()).hasSize(3);
        assertThat(doc.getBlocks().get(0).getType()).isEqualTo(BlockType.HEADING);
        assertThat(doc.getBlocks().get(1).getType()).isEqualTo(BlockType.LIST_ITEM);
        assertThat(doc.getBlocks().get(2).getType()).isEqualTo(BlockType.LIST_ITEM);
    }

    @Test
    void keepsNewlinesInsteadOfFlatteningToSpaces() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "first line\nsecond line")));
        assertThat(doc.getJoinedText()).contains("\n");
        assertThat(doc.getJoinedText()).doesNotContain("first line second line");
    }

    @Test
    void preservesPunctuationDigitsUnitsAndTerms() {
        String text = "电压 220V，温度 36.5℃，型号 MP500。";
        CleanedDocument doc = cleaner.clean(List.of(page(1, text)));
        assertThat(doc.getBlocks().get(0).getText()).isEqualTo(text);
    }

    @Test
    void chineseLengthAndContentCorrect() {
        String text = "这是一个中文段落";
        CleanedDocument doc = cleaner.clean(List.of(page(1, text)));
        TextBlock block = doc.getBlocks().get(0);
        assertThat(block.getText()).isEqualTo(text);
        assertThat(block.getLength()).isEqualTo(text.length());
    }

    @Test
    void mixedChineseEnglishStaysIntact() {
        String text = "佳能 Canon 相机 Camera 2026";
        CleanedDocument doc = cleaner.clean(List.of(page(1, text)));
        assertThat(doc.getBlocks().get(0).getText()).isEqualTo(text);
    }

    @Test
    void specialCharactersAreNotLost() {
        String text = "a@#$%^&*()_+-=[]{}|;':\",./<>?";
        CleanedDocument doc = cleaner.clean(List.of(page(1, text)));
        assertThat(doc.getBlocks().get(0).getText()).isEqualTo(text);
    }

    @Test
    void keepsWrappedLinesAsSeparateBlocks() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "The quick brown\nfox jumps high")));
        assertThat(doc.getBlocks()).hasSize(2);
        assertThat(doc.getBlocks().get(0).getText()).isEqualTo("The quick brown");
        assertThat(doc.getBlocks().get(1).getText()).isEqualTo("fox jumps high");
    }

    @Test
    void blankLineBreaksContinuation() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "alpha\n\nbeta")));
        assertThat(doc.getBlocks()).hasSize(2);
    }

    @Test
    void retainsPageNumberAcrossPages() {
        List<DocumentPageContent> pages = List.of(page(1, "page one text"), page(2, "page two text"));
        CleanedDocument doc = cleaner.clean(pages);
        assertThat(doc.getBlocks().get(0).getPageNumber()).isEqualTo(1);
        assertThat(doc.getBlocks().get(1).getPageNumber()).isEqualTo(2);
        assertThat(doc.getPages()).hasSize(2);
    }

    @Test
    void globalOffsetsMatchBlockText() {
        CleanedDocument doc = cleaner.clean(List.of(page(1, "alpha line\nbeta line")));
        for (TextBlock block : doc.getBlocks()) {
            String extracted = doc.getJoinedText()
                    .substring(block.getGlobalStart(), block.getGlobalEnd());
            assertThat(extracted).isEqualTo(block.getText());
        }
    }
}
