package com.zl.d41.document.table;

import com.zl.d41.document.table.model.DetectedTable;
import com.zl.d41.document.table.model.TableCell;
import com.zl.d41.document.table.model.TablePatterns;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 表格提取器（D41 主路径之一，KP4 冻结算法）：
 * 自定义 {@link PDFTextStripper} 子类收集 TextPosition 坐标 → 行聚类（y±ROW_TOL）→
 * 行内断格（x 间隙 &gt; COL_GAP）→ 表区滑动（连续 ≥2 行 ∧ ≥2 列）→ 列锚点归并
 * （COL_ANCHOR_TOL）→ 表头判定（表区首行）→ 类型化单元格 + 单位同记录 →
 * 合并/错位/低置信/续表检出标注（不静默错配，H4）。
 *
 * <p>与 {@code PdfDocumentParser} 关键一致性：同 sortByPosition 配置逐页取文，
 * 表格行文本以"格文本单空格拼接 + normalizeLine"与页文本行对齐（双轨定位前提）。
 * 只用 PDFBox 既有坐标能力，禁表格识别新框架（H3）。</p>
 */
@Component
public class PdfTableExtractor {

    /** 页内一个文本字形（收集自 TextPosition）。 */
    record Glyph(float x, float y, float width, String text) {}

    /** 行内一个格子（相邻字形 x 间隙 &gt; COL_GAP 断格）。 */
    record Span(float x, float width, String text) {}

    /** 一条聚类后的文本行。 */
    record Line(float y, List<Span> spans) {}

    /** 单页提取产物：表格 + 页文本原始行（行号定位用） + 图片计数。 */
    public record PageResult(int pageNumber, List<String> pageLines, int imageCount) {}

    /** 文档级提取产物。 */
    public record Extraction(List<DetectedTable> tables, Map<Integer, List<String>> pageLines,
                             Map<Integer, Integer> imageCounts) {}

    /**
     * 提取入口。
     *
     * @param bytes PDF 原始字节（导入钩子上下文，与页面持久化同源）
     * @return 文档序表格列表（页序、页内行序）；无表格返回空列表（不伪造）
     * @throws IOException PDF 读取失败（调用方按既有 PARSE_FAILED 语义处理）
     */
    public Extraction extract(byte[] bytes) throws IOException {
        List<DetectedTable> tables = new ArrayList<>();
        Map<Integer, List<String>> pageLines = new LinkedHashMap<>();
        Map<Integer, Integer> imageCounts = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageCount = document.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                // 1) 页文本行（与 PdfDocumentParser 同配置，供行号/全局偏移对齐）
                PDFTextStripper textStripper = new PDFTextStripper();
                textStripper.setStartPage(page);
                textStripper.setEndPage(page);
                textStripper.setSortByPosition(true);
                String pageText = textStripper.getText(document);
                List<String> lines = List.of(pageText.replace("\r\n", "\n").replace("\r", "\n")
                        .split("\n", -1));
                pageLines.put(page, lines);

                // 2) 坐标收集（writeString 回调=每输出行一次，携带该行的 TextPosition 集）
                CollectingStripper collector = new CollectingStripper(page);
                collector.setStartPage(page);
                collector.setEndPage(page);
                collector.setSortByPosition(true);
                collector.getText(document);

                imageCounts.put(page, countImages(document.getPage(page - 1)));

                List<Line> clusterLines = clusterToLines(collector.glyphs);
                List<DetectedTable> pageTables = detectTables(page, clusterLines, lines);

                // 3) 图片表占位（KP4：有图 ∧ 零表格 ∧ 文本行极少 → IMAGE_TABLE_NOT_PARSED）
                if (pageTables.isEmpty() && imageCounts.get(page) > 0
                        && countNonBlank(lines) <= TablePatterns.IMAGE_PAGE_MAX_TEXT_LINES) {
                    pageTables.add(DetectedTable.placeholder(page,
                            TablePatterns.FLAG_IMAGE_TABLE_NOT_PARSED));
                }
                tables.addAll(pageTables);
            }
        }
        linkContinuations(tables);
        return new Extraction(tables, pageLines, imageCounts);
    }

    /** writeString 回调收集器（非 Spring Bean，避免构造候选歧义，D38 教训）。 */
    static final class CollectingStripper extends PDFTextStripper {
        final List<Glyph> glyphs = new ArrayList<>();
        final int page;

        CollectingStripper(int page) throws IOException {
            this.page = page;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            for (TextPosition tp : textPositions) {
                String u = tp.getUnicode();
                // P1 冒烟修正：只排除空 unicode，保留空格字形——
                // 排除空白会把 "6 个月" 挤成 "6个月"（单位正则失效、真值错位）。
                if (u != null && !u.isEmpty()) {
                    glyphs.add(new Glyph(tp.getXDirAdj(), tp.getYDirAdj(),
                            tp.getWidthDirAdj(), u));
                }
            }
            super.writeString(text, textPositions);
        }
    }

    // ================= 行聚类 / 断格 =================

    static List<Line> clusterToLines(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort((a, b) -> {
            int c = Float.compare(a.y(), b.y());
            return c != 0 ? c : Float.compare(a.x(), b.x());
        });
        List<Line> lines = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        float currentY = Float.NaN;
        for (Glyph g : sorted) {
            if (current.isEmpty() || Math.abs(g.y() - currentY) <= TablePatterns.ROW_TOL) {
                current.add(g);
                currentY = current.isEmpty() ? g.y() : (currentY * (current.size() - 1) + g.y()) / current.size();
            } else {
                lines.add(toLine(current));
                current = new ArrayList<>();
                current.add(g);
                currentY = g.y();
            }
        }
        if (!current.isEmpty()) {
            lines.add(toLine(current));
        }
        return lines;
    }

    private static Line toLine(List<Glyph> rowGlyphs) {
        rowGlyphs.sort((a, b) -> Float.compare(a.x(), b.x()));
        List<Span> spans = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        float spanStart = Float.NaN;
        float lastEnd = Float.NaN;
        for (Glyph g : rowGlyphs) {
            if (Float.isNaN(spanStart)) {
                spanStart = g.x();
                sb.append(g.text());
                lastEnd = g.x() + g.width();
                continue;
            }
            if (g.x() - lastEnd > TablePatterns.COL_GAP) {
                addSpan(spans, spanStart, lastEnd, sb);
                sb.setLength(0);
                spanStart = g.x();
            }
            sb.append(g.text());
            lastEnd = Math.max(lastEnd, g.x() + g.width());
        }
        addSpan(spans, spanStart, lastEnd, sb);
        float y = rowGlyphs.get(0).y();
        return new Line(y, spans);
    }

    /** 纯空白段不产格（trim 后为空即丢弃），防假列位。 */
    private static void addSpan(List<Span> spans, float start, float end, StringBuilder sb) {
        if (Float.isNaN(start)) {
            return;
        }
        String text = sb.toString().trim();
        if (!text.isEmpty()) {
            spans.add(new Span(start, end - start, text));
        }
    }

    // ================= 表区识别 =================

    /**
     * 滑动扫描行序列：连续行"格数 ≥ MIN_TABLE_COLS"构成候选区；
     * 允许夹杂"格数 < 众数"的合并/缺格行（同区内解释，出区即断）。
     */
    static List<DetectedTable> detectTables(int page, List<Line> lines, List<String> pageLines) {
        List<DetectedTable> result = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            if (lines.get(i).spans().size() < TablePatterns.MIN_TABLE_COLS) {
                i++;
                continue;
            }
            int j = i;
            int multiCol = 0;
            while (j < lines.size()) {
                int n = lines.get(j).spans().size();
                if (n >= TablePatterns.MIN_TABLE_COLS) {
                    multiCol++;
                    j++;
                } else if (j + 1 < lines.size()
                        && lines.get(j + 1).spans().size() >= TablePatterns.MIN_TABLE_COLS) {
                    // 疑似合并/缺格行吞入区内（后续按众数列解释）
                    j++;
                } else {
                    break;
                }
            }
            if (multiCol >= TablePatterns.MIN_TABLE_ROWS) {
                DetectedTable t = buildTable(page, lines.subList(i, j), pageLines);
                if (t != null) {
                    result.add(t);
                }
            }
            i = Math.max(j, i + 1);
        }
        return result;
    }

    /**
     * 区内：列数基准=<b>表头行格数</b>（P1 冒烟修正——缺格场景下众数会漂，
     * 表头是手册表格唯一天然完整的行）；锚点=各格 x 贪心归并取前 baseCols 个；
     * 行→列映射+类型化。返回 null 表示不可解释。
     */
    private static DetectedTable buildTable(int page, List<Line> region, List<String> pageLines) {
        int baseCols = region.get(0).spans().size();
        if (baseCols < TablePatterns.MIN_TABLE_COLS) {
            return null;
        }
        // 列锚点：所有格起点按 COL_ANCHOR_TOL 贪心归并（升序）
        List<Float> anchors = new ArrayList<>();
        List<Float> starts = new ArrayList<>();
        for (Line l : region) {
            for (Span s : l.spans()) {
                starts.add(s.x());
            }
        }
        starts.sort(Float::compare);
        for (float x : starts) {
            if (anchors.isEmpty() || x - anchors.get(anchors.size() - 1) > TablePatterns.COL_ANCHOR_TOL) {
                anchors.add(x);
            }
        }
        if (anchors.size() < baseCols) {
            baseCols = anchors.size();
        }
        if (baseCols < TablePatterns.MIN_TABLE_COLS) {
            return null;
        }
        if (anchors.size() > baseCols) {
            anchors = anchors.subList(0, baseCols);
        }
        final int modeCols = baseCols;

        DetectedTable table = new DetectedTable(page);
        int deviated = 0;
        for (int r = 0; r < region.size(); r++) {
            Line line = region.get(r);
            List<TableCell> rowCells = mapLineToCells(line, anchors, modeCols, table);
            if (rowCells == null) {
                return null;
            }
            if (line.spans().size() != modeCols) {
                deviated++;
            }
            String lineText = joinLineText(line);
            int lineIndex = indexOfLine(pageLines, lineText);
            table.addRegionLine(lineText, lineIndex);
            if (r == 0) {
                for (TableCell c : rowCells) {
                    table.getHeader().add(c.value() == null ? "" : c.value());
                }
            } else {
                long nonNull = rowCells.stream().filter(c -> !c.isNull()).count();
                if (nonNull <= 1) {
                    table.addFlag(TablePatterns.FLAG_NEAR_EMPTY_ROW);
                    continue;
                }
                table.addDataRow(rowCells, lineIndex);
            }
        }
        if (table.getHeader().isEmpty()) {
            return null;
        }
        if ((double) deviated / region.size() > TablePatterns.LOW_CONF_SHARE) {
            table.addFlag(TablePatterns.FLAG_LOW_CONFIDENCE);
            table.setConfidence(TablePatterns.CONFIDENCE_LOW);
        }
        if (table.getRows().isEmpty() && table.hasFlag(TablePatterns.FLAG_NEAR_EMPTY_ROW)) {
            return null; // 无数据行不产生空记录（D10）
        }
        return table;
    }

    /** 行格 → modeCols 列向量：锚点归属 + 合并/错位检出；返回 null 表示不可解释。 */
    private static List<TableCell> mapLineToCells(Line line, List<Float> anchors,
                                                  int modeCols, DetectedTable table) {
        List<Span> spans = line.spans();
        List<TableCell> cells = new ArrayList<>(modeCols);
        for (int c = 0; c < modeCols; c++) {
            cells.add(TableCell.missing());
        }
        boolean misaligned = false;
        boolean merged = false;
        boolean shortRow = spans.size() < modeCols;
        for (Span s : spans) {
            int anchorIdx = nearestAnchor(anchors, s.x());
            // 合并判定（KP4 修正 v2）：仅"行格数<表头列数"时启用区间覆盖语义——
            // 格右缘越过下一锚点所在列距中点（0.5）；正常单列格右缘不过列中点（夹具实测 153 vs 136/160）。
            int coveredLast = anchorIdx;
            if (shortRow) {
                for (int a = anchorIdx + 1; a < Math.min(anchors.size(), modeCols); a++) {
                    float boundary = anchors.get(anchorIdx)
                            + 0.5f * (anchors.get(a) - anchors.get(anchorIdx));
                    if (s.x() + s.width() >= boundary) {
                        coveredLast = a;
                    } else {
                        break;
                    }
                }
            }
            if (coveredLast > anchorIdx && !s.text().isEmpty()) {
                merged = true;
            }
            if (anchorIdx >= modeCols) {
                anchorIdx = modeCols - 1;
                misaligned = true;
            }
            TableCell typed = TableCell.typeOf(s.text());
            if (cells.get(anchorIdx).isNull()) {
                cells.set(anchorIdx, typed);
            } else {
                // 同列已有值：保留首值并标注错位（不静默覆盖）
                misaligned = true;
            }
        }
        if (merged) {
            table.addFlag(TablePatterns.FLAG_MERGED_CELL);
        }
        if (misaligned || (shortRow && !merged)) {
            table.addFlag(TablePatterns.FLAG_COLUMN_MISALIGNMENT);
        }
        return cells;
    }

    private static int nearestAnchor(List<Float> anchors, float x) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < anchors.size(); i++) {
            float d = Math.abs(anchors.get(i) - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 表格行的"页文本行"形态：格文本单空格拼接（与 PDFTextStripper 词间单空格口径一致）。 */
    private static String joinLineText(Line line) {
        StringBuilder sb = new StringBuilder();
        for (Span s : line.spans()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.text());
        }
        return sb.toString();
    }

    private static int indexOfLine(List<String> pageLines, String lineText) {
        String normalized = TablePatterns.normalizeLine(lineText);
        if (normalized.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < pageLines.size(); i++) {
            if (TablePatterns.normalizeLine(pageLines.get(i)).equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    // ================= 跨页/同页续表（KP4：新记录+回链+表头不入数据行） =================

    /** 续表回链（跨页/同页通用，KP4：新记录+回链+重复表头不入数据行）；文本表复用同一路径。 */
    public static void linkContinuations(List<DetectedTable> tables) {
        DetectedTable previous = null;
        int previousOrdinal = -1;
        int ordinal = 0;
        for (DetectedTable t : tables) {
            if (previous != null && !t.getHeader().isEmpty()
                    && t.getHeader().equals(previous.getHeader())
                    && !previous.hasFlag(TablePatterns.FLAG_IMAGE_TABLE_NOT_PARSED)) {
                t.markContinuation(previous.getPageNumber(), previousOrdinal);
                t.addFlag(TablePatterns.FLAG_CONT_REPEAT_HEADER);
            }
            if (!t.hasFlag(TablePatterns.FLAG_IMAGE_TABLE_NOT_PARSED)) {
                previous = t;
                previousOrdinal = ordinal;
            }
            ordinal++;
        }
    }

    private static int countImages(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) {
            return 0;
        }
        int count = 0;
        for (org.apache.pdfbox.cos.COSName name : resources.getXObjectNames()) {
            PDXObject xo = resources.getXObject(name);
            if (xo instanceof PDImageXObject) {
                count++;
            }
        }
        return count;
    }

    private static int countNonBlank(List<String> lines) {
        int n = 0;
        for (String l : lines) {
            if (!l.isBlank()) {
                n++;
            }
        }
        return n;
    }
}
