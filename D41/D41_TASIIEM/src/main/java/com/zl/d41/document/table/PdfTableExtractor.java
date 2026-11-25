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

    /** 行内一个格子（相邻字形 x 间隙 &gt; COL_GAP 断格）。glyphs=段内字形明细（R03 分裂原语用）。 */
    record Span(float x, float width, String text, List<Glyph> glyphs) {}

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
                List<DetectedTable> pageTables =
                        mergeAdjacentFragments(detectTables(page, clusterLines, lines));

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
        List<Glyph> run = new ArrayList<>();
        float spanStart = Float.NaN;
        float lastEnd = Float.NaN;
        for (Glyph g : rowGlyphs) {
            if (Float.isNaN(spanStart)) {
                spanStart = g.x();
                sb.append(g.text());
                run.add(g);
                lastEnd = g.x() + g.width();
                continue;
            }
            if (g.x() - lastEnd > TablePatterns.COL_GAP) {
                addSpan(spans, spanStart, lastEnd, sb, run);
                sb.setLength(0);
                run.clear();
                spanStart = g.x();
            }
            sb.append(g.text());
            run.add(g);
            lastEnd = Math.max(lastEnd, g.x() + g.width());
        }
        addSpan(spans, spanStart, lastEnd, sb, run);
        float y = rowGlyphs.get(0).y();
        return refineLine(new Line(y, spans));
    }

    /** 纯空白段不产格（trim 后为空即丢弃），防假列位。 */
    private static void addSpan(List<Span> spans, float start, float end, StringBuilder sb,
                                List<Glyph> glyphs) {
        if (Float.isNaN(start)) {
            return;
        }
        String text = sb.toString().trim();
        if (!text.isEmpty()) {
            spans.add(new Span(start, end - start, text, List.copyOf(glyphs)));
        }
    }

    // ================= R03 行精化（T1 表头列名锚定 / T5 值黏连分隔） =================

    /**
     * 行内 span 二级分裂原语（R03）：
     * <ul>
     *   <li>T1：整行判定为"型号表头行"（分裂后型号 token ≥2 且 ≥行 token 半数，
     *       token 形如 {@code G11303□S/C11010ES}）→ 行内所有 span 按**型号起点或空白**
     *       边界分裂（型号粘连串无空格也能切开，p397/p398 实测形态）；</li>
     *   <li>T5：普通行的 span 仅在**空白字形分界**且"前缀非数值形态 + 后缀完整数值字面"
     *       时切成两 span（E4「LL（不帶剎車） 147.5」）；「5.5 mm」前缀为数值 → 不切，
     *       单位同记录契约（D10）不受影响。</li>
     * </ul>
     * 分裂不删除任何字形文本——只重排 span 边界，段序与页文本行对齐口径不变。
     */
    static Line refineLine(Line line) {
        List<Span> spans = line.spans();
        if (spans.isEmpty()) {
            return line;
        }
        // T1：整行先按空白/型号起点预切 token，若构成"型号表头行"（型号 token ≥2
        // 且 ≥非空 token 半数）→ 全行所有 span 按字形边界分裂（型号起点或空白分界）。
        if (isModelHeaderLine(spans)) {
            List<Span> out = new ArrayList<>();
            for (Span s : spans) {
                splitSpanForHeader(s, out);
            }
            return new Line(line.y(), out);
        }
        // T5：普通行仅切"非数值前缀 + 空白 + 完整数值字面"边界（E4 黏连形态）。
        List<Span> out = new ArrayList<>(spans.size());
        for (Span s : spans) {
            splitStickyValue(s, out);
        }
        return new Line(line.y(), out);
    }

    /** 型号表头行判据：token 化后型号 token ≥2 且 ≥非空 token 数的一半。 */
    static boolean isModelHeaderLine(List<Span> spans) {
        int tokens = 0;
        int models = 0;
        for (Span s : spans) {
            for (String t : s.text().split("\\s+")) {
                if (t.isBlank()) {
                    continue;
                }
                for (String piece : TablePatterns.MODEL_COL_START.split(t)) {
                    if (piece.isBlank()) {
                        continue;
                    }
                    tokens++;
                    if (TablePatterns.MODEL_COL_TOKEN.matcher(piece).matches()) {
                        models++;
                    }
                }
            }
        }
        return models >= 2 && models * 2 >= tokens;
    }

    /** 表头行 span 分裂：按空白字形边界 + 粘连型号起点（字形级 x 保真）。 */
    private static void splitSpanForHeader(Span s, List<Span> out) {
        List<Glyph> gs = s.glyphs();
        int start = 0;
        for (int i = 1; i < gs.size(); i++) {
            boolean cut = false;
            Glyph prev = gs.get(i - 1);
            Glyph g = gs.get(i);
            if (isBlankGlyph(prev) || isBlankGlyph(g)) {
                cut = true; // 空白分界（空白字形归前段，trim 处理）
            } else if (startsModelToken(gs, i)) {
                cut = true; // 粘连型号串边界（E11305□SG11306□S）
            }
            if (cut) {
                emitSpan(gs, start, i, out);
                start = i;
            }
        }
        emitSpan(gs, start, gs.size(), out);
    }

    /**
     * i 起是否新列名 token 起点：字母+4~5 位数字 lookahead；回溯跳过 □/S/ES 尾缀粘连
     * （G11303□S+G 与 G11303□+S 两种断行形态都认）。
     */
    private static boolean startsModelToken(List<Glyph> gs, int i) {
        int len = gs.size();
        if (i >= len) {
            return false;
        }
        String at = gs.get(i).text();
        if (at.length() != 1 || !Character.isLetter(at.charAt(0))) {
            return false;
        }
        int digits = 0;
        for (int k = i + 1; k < len && k <= i + 6; k++) {
            Character cp = digitOf(gs.get(k).text());
            if (cp == null) {
                break;
            }
            digits++;
        }
        if (digits >= 4 && digits <= 5) {
            return true;
        }
        // 尾缀回退：前 1~2 字形为 S/□ 时，视当前为下一型号起点（如 □S|G 之间的 S 前断）
        if (digits == 0 && i >= 2) {
            String p1 = gs.get(i - 1).text();
            if (p1.equals("S")) {
                String p2 = gs.get(i - 2).text();
                if (p2.equals("\u25a1")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Character digitOf(String t) {
        return (t.length() == 1 && Character.isDigit(t.charAt(0))) ? t.charAt(0) : null;
    }

    /**
     * T5：span 内在"空白字形 + 后缀完整数值"边界切一刀（仅一次，取首个满足位）：
     * 前缀整体非数值形态（NUMERIC_SUFFIX 不匹配）且空格后连续字形恰好构成完整数值字面。
     * 「5.5 mm」前缀即数值 → 不切（单位同记录 D10）；「C11010ES」无空白 → 不切。
     */
    private static void splitStickyValue(Span s, List<Span> out) {
        List<Glyph> gs = s.glyphs();
        for (int i = 1; i < gs.size(); i++) {
            if (!isBlankGlyph(gs.get(i))) {
                continue;
            }
            // 空白段结束位置 j：数值段起点
            int j = i;
            while (j < gs.size() && isBlankGlyph(gs.get(j))) {
                j++;
            }
            if (j >= gs.size()) {
                break; // 尾部空白，无候选值段
            }
            String prefix = glyphsText(gs, 0, i).trim();
            String suffix = glyphsText(gs, j, gs.size()).trim();
            if (prefix.isEmpty() || suffix.isEmpty()) {
                break;
            }
            boolean prefixNumeric = TablePatterns.NUMERIC_SUFFIX.matcher(prefix).matches();
            boolean suffixNumeric = TablePatterns.NUMERIC_SUFFIX.matcher(suffix).matches();
            if (!prefixNumeric && suffixNumeric) {
                emitSpan(gs, 0, i, out);
                emitSpan(gs, j, gs.size(), out);
                return;
            }
            break; // 只评估首个空白边界（保守）
        }
        out.add(s); // 无切点：原 span 原样保留（text/width/glyphs 零信息损失）
    }

    private static String glyphsText(List<Glyph> gs, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(gs.get(i).text());
        }
        return sb.toString();
    }

    /** 由字形子区间产出新 span（x=首字形 x，width=子区间跨度，glyphs 同步切片）。 */
    private static void emitSpan(List<Glyph> gs, int from, int to, List<Span> out) {
        String text = glyphsText(gs, from, to).trim();
        if (text.isEmpty()) {
            return;
        }
        Glyph first = gs.get(from);
        Glyph last = gs.get(to - 1);
        float end = last.x() + last.width();
        out.add(new Span(first.x(), end - first.x(), text,
                List.copyOf(gs.subList(from, to))));
    }

    // ================= R03 T4：参数块碎片按 lineIndex 邻接合并 =================

    static List<DetectedTable> mergeAdjacentFragments(List<DetectedTable> tables) {
        List<DetectedTable> out = new ArrayList<>();
        DetectedTable cur = null;
        for (DetectedTable t : tables) {
            if (cur != null && canMergeFragments(cur, t)) {
                appendFragment(cur, t);
            } else {
                cur = t;
                out.add(cur);
            }
        }
        return out;
    }

    /** 合并条件：同页、非续表/占位、列数相等、双方均为碎片形态、lineIndex 严格邻接。 */
    private static boolean canMergeFragments(DetectedTable a, DetectedTable b) {
        if (a.getPageNumber() != b.getPageNumber()
                || a.isContinuation() || b.isContinuation()) {
            return false;
        }
        int cols = a.getHeader().size();
        if (cols < TablePatterns.MIN_TABLE_COLS || b.getHeader().size() != cols) {
            return false;
        }
        if (a.getConfidence().equals(TablePatterns.CONFIDENCE_NA)
                || b.getConfidence().equals(TablePatterns.CONFIDENCE_NA)) {
            return false; // 占位表不参与
        }
        if ((a.getRowTexts().size() > TablePatterns.FRAGMENT_MAX_REGION_LINES
                && !a.hasFlag(TablePatterns.FLAG_FRAGMENT_MERGED))
                || b.getRowTexts().size() > TablePatterns.FRAGMENT_MAX_REGION_LINES) {
            return false;
        }
        int aLast = -1;
        for (int idx : a.getLineIndexes()) {
            if (idx > aLast) {
                aLast = idx;
            }
        }
        int bFirst = Integer.MAX_VALUE;
        for (int idx : b.getLineIndexes()) {
            if (idx >= 0 && idx < bFirst) {
                bFirst = idx;
            }
        }
        return aLast >= 0 && bFirst != Integer.MAX_VALUE && bFirst >= aLast
                && bFirst - aLast <= TablePatterns.FRAGMENT_MERGE_MAX_GAP_LINES;
    }

    /** 拼接：b 的伪表头降格为类型化数据行（不静默丢弃任何原始行文本），行区/flags 全并。 */
    private static void appendFragment(DetectedTable a, DetectedTable b) {
        int bHeaderLine = b.getLineIndexes().isEmpty() ? -1 : b.getLineIndexes().get(0);
        List<TableCell> headerAsRow = new ArrayList<>(b.getHeader().size());
        for (String h : b.getHeader()) {
            headerAsRow.add(TableCell.typeOf(h));
        }
        a.addDataRow(headerAsRow, bHeaderLine);
        for (int i = 0; i < b.getRows().size(); i++) {
            Integer dn = b.getRowDataLineNumbers().get(i);
            a.addDataRow(b.getRows().get(i), dn == null ? -1 : dn - 1);
        }
        for (int i = 0; i < b.getRowTexts().size(); i++) {
            a.addRegionLine(b.getRowTexts().get(i), b.getLineIndexes().get(i));
        }
        a.getFlags().addAll(b.getFlags());
        a.addFlag(TablePatterns.FLAG_FRAGMENT_MERGED);
        if (b.getConfidence().equals(TablePatterns.CONFIDENCE_LOW)) {
            a.setConfidence(TablePatterns.CONFIDENCE_LOW);
        }
    }

    // ================= 表区识别 =================

    /**
     * 滑动扫描行序列：连续行"格数 ≥ MIN_TABLE_COLS"构成候选区；
     * 允许夹杂"格数 < 众数"的合并/缺格行（同区内解释，出区即断）。
     */
    static List<DetectedTable> detectTables(int page, List<Line> rawLines, List<String> pageLines) {
        // R03：行精化统一在表区识别前执行（refineLine 幂等；生产路径 toLine 已精化，
        // 此处为测试/复用入口兜底同构输入）。
        List<Line> lines = new ArrayList<>(rawLines.size());
        for (Line l : rawLines) {
            lines.add(refineLine(l));
        }
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
     * 表头是手册表格唯一天然完整的行）；R03-T1 起<b>列锚点=表头行格 x</b>
     * （原"全行贪心聚类+subList(0,baseCols)"被 p396 实证证伪：数值列跨行 x 漂移
     * ±6–8pt 制造伪锚，截断把右侧真实列锚整体丢弃 → E1 列丢失；表头经 refineLine
     * 型号分裂后即为天然完整列网格）；行→列映射+类型化。返回 null 表示不可解释。
     */
    private static DetectedTable buildTable(int page, List<Line> region, List<String> pageLines) {
        int baseCols = region.get(0).spans().size();
        if (baseCols < TablePatterns.MIN_TABLE_COLS) {
            return null;
        }
        // R03-T1：表头锚定——锚点序列=表头行各 span 起点（升序，行内本升序）
        List<Float> anchors = new ArrayList<>(baseCols);
        for (Span s : region.get(0).spans()) {
            anchors.add(s.x());
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

    /** 行格 → modeCols 列向量（R03：表头锚区间归列，T1/T3 同机制）；null=不可解释。 */
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
            int anchorIdx = intervalColumn(anchors, s.x());
            // 合并判定（KP4 修正 v2 语义迁移到区间模型）：仅"行格数<表头列数"时启用
            // 区间覆盖——格右缘越过后续列锚的左边界（前一中点）即计覆盖该列。
            int coveredLast = anchorIdx;
            if (shortRow) {
                for (int a = anchorIdx + 1; a < anchors.size() && a < modeCols; a++) {
                    float boundary = anchors.get(a - 1)
                            + 0.5f * (anchors.get(a) - anchors.get(a - 1));
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

    /**
     * 区间归列（R03-T3）：列边界=相邻锚中点；x ≤ 首个中点 → 0 列，依次右推，
     * 越过最右中点 → 末列；锚 0 以左（长标签行左漂）归 0 列——列位由表头锚唯一决定，
     * 不再受数据行 x 漂移生成的伪锚影响。
     */
    static int intervalColumn(List<Float> anchors, float x) {
        for (int i = 1; i < anchors.size(); i++) {
            float mid = anchors.get(i - 1) + 0.5f * (anchors.get(i) - anchors.get(i - 1));
            if (x <= mid) {
                return i - 1;
            }
        }
        return Math.max(0, anchors.size() - 1);
    }

    /** 空白字形判定（T1/T5 分裂分界用）：text trim 为空即空白。 */
    private static boolean isBlankGlyph(Glyph g) {
        return g.text() == null || g.text().isBlank();
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
            // R01 整改（R02 反例红线）：仅"表头相同"不构成续表证据——同页两张独立
            // 同表头表会被误链。收敛为跨页续表：页序前进 + 表头一致才回链；
            // 证据不足宁可不链（漏链保持独立表不失真，误链产生虚假父子关系）。
            if (previous != null && !t.getHeader().isEmpty()
                    && t.getHeader().equals(previous.getHeader())
                    && t.getPageNumber() > previous.getPageNumber()
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
