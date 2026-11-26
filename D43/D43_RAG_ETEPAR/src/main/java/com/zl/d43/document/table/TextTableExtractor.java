package com.zl.d43.document.table;

import com.zl.d43.document.table.model.DetectedTable;
import com.zl.d43.document.table.model.TableCell;
import com.zl.d43.document.table.model.TablePatterns;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本管道表提取器（主路径之二）：md 管道表（{@code | a | b |} + 分隔行）与
 * tab 分隔表（连续行 ≥2 制表符）；只用 JDK 文本规则，禁新框架（H3）。
 *
 * <p>与 {@link PdfTableExtractor} 输出同构（{@link DetectedTable}），
 * 双轨定位/持久化/续表回链共用同一服务链路；行索引=原始页文本行 0-based，天然精确。</p>
 */
@Component
public class TextTableExtractor {

    /** 对单页文本行提取全部表格。 */
    public List<DetectedTable> extract(int pageNumber, List<String> pageLines) {
        List<DetectedTable> tables = new ArrayList<>();
        int i = 0;
        while (i < pageLines.size()) {
            if (looksLikePipeRow(pageLines.get(i)) && i + 1 < pageLines.size()
                    && TablePatterns.PIPE_SEPARATOR.matcher(pageLines.get(i + 1).trim()).matches()) {
                ScanResult r = readPipeTable(pageNumber, pageLines, i);
                if (r.table() != null) {
                    tables.add(r.table());
                }
                i = Math.max(r.nextLine(), i + 1);
                continue;
            }
            if (looksLikeTabRow(pageLines.get(i))
                    && i + 1 < pageLines.size() && looksLikeTabRow(pageLines.get(i + 1))) {
                ScanResult r = readTabTable(pageNumber, pageLines, i);
                if (r.table() != null) {
                    tables.add(r.table());
                }
                i = Math.max(r.nextLine(), i + 1);
                continue;
            }
            i++;
        }
        return tables;
    }

    private record ScanResult(DetectedTable table, int nextLine) {}

    private static boolean looksLikePipeRow(String line) {
        String s = line.trim();
        return s.startsWith("|") && s.length() > 1 && s.chars().filter(c -> c == '|').count() >= 2;
    }

    private static boolean looksLikeTabRow(String line) {
        return !line.isBlank() && !looksLikePipeRow(line)
                && line.chars().filter(c -> c == '\t').count() >= 2;
    }

    /** 管道表：首行表头，分隔行后连续 | 行为数据行；空串单元=null 占位（A4）。 */
    private ScanResult readPipeTable(int page, List<String> lines, int headerLine) {
        DetectedTable t = new DetectedTable(page);
        List<String> headerCells = splitPipe(lines.get(headerLine));
        for (String cell : headerCells) {
            t.getHeader().add(TablePatterns.normalizeLine(cell));
        }
        t.addRegionLine(lines.get(headerLine).trim(), headerLine);
        int i = headerLine + 2;
        int deviated = 0;
        int bodyRows = 0;
        while (i < lines.size() && looksLikePipeRow(lines.get(i))) {
            List<String> cells = splitPipe(lines.get(i));
            if (cells.size() != headerCells.size()) {
                deviated++;
                t.addFlag(TablePatterns.FLAG_COLUMN_MISALIGNMENT);
            }
            List<TableCell> row = alignToHeader(cells, headerCells.size());
            t.addRegionLine(lines.get(i).trim(), i);
            long nonNull = row.stream().filter(c -> !c.isNull()).count();
            if (nonNull <= 1) {
                t.addFlag(TablePatterns.FLAG_NEAR_EMPTY_ROW);
            } else {
                t.addDataRow(row, i);
            }
            bodyRows++;
            i++;
        }
        DetectedTable result = t.getRows().isEmpty() ? null : t;
        if (result != null && bodyRows > 0 && (double) deviated / bodyRows > TablePatterns.LOW_CONF_SHARE) {
            result.addFlag(TablePatterns.FLAG_LOW_CONFIDENCE);
            result.setConfidence(TablePatterns.CONFIDENCE_LOW);
        }
        return new ScanResult(result, i);
    }

    /** tab 表：首行表头，连续同构行为数据行；列数=表头切分列数。 */
    private ScanResult readTabTable(int page, List<String> lines, int headerLine) {
        DetectedTable t = new DetectedTable(page);
        String[] header = lines.get(headerLine).split("\t", -1);
        for (String cell : header) {
            t.getHeader().add(TablePatterns.normalizeLine(cell));
        }
        t.addRegionLine(lines.get(headerLine), headerLine);
        int i = headerLine + 1;
        int deviated = 0;
        int bodyRows = 0;
        while (i < lines.size() && looksLikeTabRow(lines.get(i))) {
            String[] cells = lines.get(i).split("\t", -1);
            if (cells.length != header.length) {
                deviated++;
                t.addFlag(TablePatterns.FLAG_COLUMN_MISALIGNMENT);
            }
            List<TableCell> row = alignToHeader(List.of(cells), header.length);
            t.addRegionLine(lines.get(i), i);
            long nonNull = row.stream().filter(c -> !c.isNull()).count();
            if (nonNull <= 1) {
                t.addFlag(TablePatterns.FLAG_NEAR_EMPTY_ROW);
            } else {
                t.addDataRow(row, i);
            }
            bodyRows++;
            i++;
        }
        DetectedTable result = t.getRows().isEmpty() ? null : t;
        if (result != null && bodyRows > 0
                && (double) deviated / bodyRows > TablePatterns.LOW_CONF_SHARE) {
            result.addFlag(TablePatterns.FLAG_LOW_CONFIDENCE);
            result.setConfidence(TablePatterns.CONFIDENCE_LOW);
        }
        return new ScanResult(result, i);
    }

    /** 管道行拆格：去首尾竖线后按 | 切分并 trim。 */
    private static List<String> splitPipe(String line) {
        String s = line.trim();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String part : s.split("\\|", -1)) {
            cells.add(part.trim());
        }
        return cells;
    }

    /** 对齐表头列数：不足 null 补位、超出截断并标注（不串列，H4）。 */
    private static List<TableCell> alignToHeader(List<String> cells, int headerCols) {
        List<TableCell> row = new ArrayList<>(headerCols);
        for (int c = 0; c < headerCols; c++) {
            row.add(c < cells.size() ? TableCell.typeOf(cells.get(c)) : TableCell.missing());
        }
        if (cells.size() > headerCols) {
            // 超出列已截断：调用方经 deviated 统一标注
        }
        return row;
    }
}
