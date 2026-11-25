package com.zl.d41.document.table;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * D41-R03 行结构转储探针（T1/T2/T3 设计输入）：对指定页 dump clusterToLines 后的
 * Line/Span 明细（y、每 span 的 x/width/text），暴露真实列坐标形态。
 * 用法: java D41SpanDump <pdf> <page> <out.txt>
 */
public final class D41SpanDump {

    public static void main(String[] args) throws Exception {
        File pdf = new File(args[0]);
        int page = Integer.parseInt(args[1]);
        File out = new File(args[2]);

        byte[] bytes = Files.readAllBytes(pdf.toPath());
        List<PdfTableExtractor.Glyph> glyphs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            DumpStripper ds = new DumpStripper(glyphs);
            ds.setStartPage(page);
            ds.setEndPage(page);
            ds.setSortByPosition(true);
            ds.getText(doc);
        }
        List<PdfTableExtractor.Line> lines = PdfTableExtractor.clusterToLines(glyphs);
        StringBuilder sb = new StringBuilder();
        sb.append("PAGE=").append(page).append(" LINES=").append(lines.size()).append('\n');
        int idx = 0;
        for (PdfTableExtractor.Line l : lines) {
            sb.append(String.format("L%03d y=%.1f spans=%d", idx++, l.y(), l.spans().size()));
            for (PdfTableExtractor.Span s : l.spans()) {
                sb.append(String.format(" [x=%.1f w=%.1f t=%s]", s.x(), s.width(), s.text()));
            }
            sb.append('\n');
        }
        Files.writeString(out.toPath(), sb.toString());
        System.out.println("WROTE=" + out.getAbsolutePath() + " lines=" + idx);
    }

    static final class DumpStripper extends PDFTextStripper {
        private final List<PdfTableExtractor.Glyph> sink;

        DumpStripper(List<PdfTableExtractor.Glyph> sink) throws IOException {
            this.sink = sink;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            for (TextPosition tp : textPositions) {
                String u = tp.getUnicode();
                if (u != null && !u.isEmpty()) {
                    sink.add(new PdfTableExtractor.Glyph(tp.getXDirAdj(), tp.getYDirAdj(),
                            tp.getWidthDirAdj(), u));
                }
            }
            super.writeString(text, textPositions);
        }
    }
}
