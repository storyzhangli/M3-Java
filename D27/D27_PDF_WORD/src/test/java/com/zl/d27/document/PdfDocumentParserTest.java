package com.zl.d27.document;

import com.zl.d27.document.exception.DocumentParseException;
import com.zl.d27.document.model.DocumentPageContent;
import com.zl.d27.document.support.PdfDocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PDF 解析器测试：使用 PDFBox 真实生成的 PDF，不用 Mock 替代真实解析。
 */
class PdfDocumentParserTest {

    private final PdfDocumentParser parser = new PdfDocumentParser();

    @Test
    void multiPagePdfExtractsWithPageBoundaries() {
        byte[] pdf = buildPdf(
                new String[]{"Canon Camera Guide", "Second Page Details 123"},
                new boolean[]{false, false});

        List<DocumentPageContent> pages = parser.parse(pdf);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).getPageNumber()).isEqualTo(1);
        assertThat(pages.get(1).getPageNumber()).isEqualTo(2);
        assertThat(pages.get(0).getContent()).contains("Canon Camera Guide");
        assertThat(pages.get(1).getContent()).contains("Second Page Details 123");
        assertThat(pages.get(0).getBlank()).isFalse();
        // 页面边界：第一页文本不应串到第二页
        assertThat(pages.get(1).getContent()).doesNotContain("Canon Camera Guide");
    }

    @Test
    void blankPageKeptWithPosition() {
        byte[] pdf = buildPdf(
                new String[]{"Only First Page", ""},
                new boolean[]{false, true});

        List<DocumentPageContent> pages = parser.parse(pdf);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(1).getPageNumber()).isEqualTo(2);
        assertThat(pages.get(1).getBlank()).isTrue();
        // 空白页定位不丢失
        assertThat(pages.get(1).getContent()).isEmpty();
    }

    @Test
    void corruptedPdfThrowsParseException() {
        byte[] garbage = {1, 2, 3, 4, 5, 6, 7, 8};
        assertThatThrownBy(() -> parser.parse(garbage))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("PDF");
    }

    @Test
    void emptyBytesThrowsParseException() {
        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("无内容");
    }

    @Test
    void plainTextMisroutedToPdfParserFails() {
        byte[] text = "this is not a pdf".getBytes();
        assertThatThrownBy(() -> parser.parse(text))
                .isInstanceOf(DocumentParseException.class);
    }

    @Test
    void mixedChineseEnglishDigitsExtracted() {
        File font = locateCjkFont();
        // 无可用 CJK 字体的环境（如精简 CI）跳过，不伪造通过；本机 Windows 具备字体时真实执行。
        assumeTrue(font != null, "环境未提供常见 CJK 字体，跳过中英文混合真实解析");

        String mixed = "佳能 Canon 相机 Camera 2026 型号 MP500";
        byte[] pdf = buildCjkPdf(font, mixed);

        List<DocumentPageContent> pages = parser.parse(pdf);
        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).getBlank()).isFalse();
        assertThat(pages.get(0).getContent())
                .contains("佳能")
                .contains("Canon")
                .contains("2026")
                .contains("MP500");
    }

    private File locateCjkFont() {
        String[] candidates = {
                "C:\\Windows\\Fonts\\simhei.ttf",
                "C:\\Windows\\Fonts\\simkai.ttf",
                "C:\\Windows\\Fonts\\Deng.ttf",
                "C:\\Windows\\Fonts\\msyh.ttc",
                "C:\\Windows\\Fonts\\simsun.ttc"};
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists()) {
                return f;
            }
        }
        return null;
    }

    private byte[] buildCjkPdf(File fontFile, String text) {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font = PDType0Font.load(doc, fontFile);
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("CJK 测试 PDF 生成失败", e);
        }
    }

    /**
     * 用 PDFBox 真实生成多页 PDF；blank 标记的页不写文本。
     */
    private byte[] buildPdf(String[] texts, boolean[] blanks) {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < texts.length; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (!blanks[i]) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(font, 14);
                        cs.newLineAtOffset(60, 700);
                        cs.showText(texts[i]);
                        cs.endText();
                    }
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("测试 PDF 生成失败", e);
        }
    }
}
