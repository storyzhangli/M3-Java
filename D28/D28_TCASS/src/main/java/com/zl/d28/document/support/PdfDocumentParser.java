package com.zl.d28.document.support;

import com.zl.d28.document.exception.DocumentParseException;
import com.zl.d28.document.model.DocumentPageContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文档解析器，基于 Apache PDFBox 3.0.7。
 *
 * <p>PDFBox 3.x 使用 {@link Loader#loadPDF(byte[])} 加载（非旧版 PDDocument.load），
 * 按物理页逐页提取，保留页面边界与从 1 开始的物理页码；空白页也返回，
 * 由 blank 标记，避免页面定位丢失。</p>
 *
 * <p>本组件只负责解析，不写库、不做 OCR、不做 Chunk 切分。</p>
 */
@Component
public class PdfDocumentParser {

    /**
     * 解析 PDF 原始字节，返回每个物理页的提取内容（含空白页）。
     *
     * @throws DocumentParseException 加载失败、损坏 PDF 或无有效页面
     */
    public List<DocumentPageContent> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentParseException("解析失败：PDF 文档无内容");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                throw new DocumentParseException("解析失败：PDF 不包含有效页面");
            }
            List<DocumentPageContent> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                // 物理页码 1-based；逐页设置起止页，保证页面边界不被错误拼接。
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.setSortByPosition(true);
                String text = stripper.getText(document);
                pages.add(buildPage(page, text));
            }
            return pages;
        } catch (IOException e) {
            throw new DocumentParseException("PDF 解析失败：文件损坏或格式非法");
        }
    }

    private DocumentPageContent buildPage(int pageNumber, String rawText) {
        String text = rawText == null ? "" : rawText;
        boolean hasUsable = text.chars().anyMatch(Character::isLetterOrDigit);

        DocumentPageContent page = new DocumentPageContent();
        page.setPageNumber(pageNumber);
        page.setContent(text);
        page.setContentLength(text.length());
        page.setBlank(!hasUsable);
        return page;
    }
}
