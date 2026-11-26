package com.zl.d43.document.table.model;

import com.zl.d43.document.table.model.TableCell;
import com.zl.d43.document.table.model.TablePatterns;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检出表格（抽取层内部模型，持久化前）：表头 + 类型化数据行 + 页/行定位 + 退化标注。
 *
 * <p>行定位口径（KP4）：</p>
 * <ul>
 *   <li>{@link #rowTexts}/{@link #lineIndexes} 对齐表区<b>全部</b>物理行（含表头行
 *       与未落库的近空行），lineIndexes=页文本 0-based 行号、-1=未对齐；</li>
 *   <li>{@link #rows}/{@link #rowDataLineNumbers} 对齐<b>落库</b>数据行，
 *       rowDataLineNumbers=页文本 1-based 行号（source_line_number 来源）。</li>
 * </ul>
 */
public final class DetectedTable {

    private final int pageNumber;
    private final List<String> header = new ArrayList<>();
    private final List<List<TableCell>> rows = new ArrayList<>();
    private final List<Integer> rowDataLineNumbers = new ArrayList<>();
    private final List<String> rowTexts = new ArrayList<>();
    private final List<Integer> lineIndexes = new ArrayList<>();
    private final Set<String> flags = new LinkedHashSet<>();
    private String confidence = TablePatterns.CONFIDENCE_HIGH;
    private int continuationPage = -1;
    private int continuationOrdinal = -1;

    public DetectedTable(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public List<String> getHeader() {
        return header;
    }

    public List<List<TableCell>> getRows() {
        return rows;
    }

    public List<Integer> getRowDataLineNumbers() {
        return rowDataLineNumbers;
    }

    public List<String> getRowTexts() {
        return rowTexts;
    }

    public List<Integer> getLineIndexes() {
        return lineIndexes;
    }

    public Set<String> getFlags() {
        return flags;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public void addFlag(String flag) {
        flags.add(flag);
    }

    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    /** 记录表区一行（任意行：表头/数据/近空行）。 */
    public void addRegionLine(String lineText, int lineIndex) {
        rowTexts.add(lineText);
        lineIndexes.add(lineIndex);
    }

    /** 记录落库数据行（与 rows 同步调用）。 */
    public void addDataRow(List<TableCell> cells, int lineIndex) {
        rows.add(cells);
        rowDataLineNumbers.add(lineIndex < 0 ? null : lineIndex + 1);
    }

    public void markContinuation(int page, int ordinal) {
        this.continuationPage = page;
        this.continuationOrdinal = ordinal;
    }

    public boolean isContinuation() {
        return continuationPage > 0;
    }

    public int getContinuationPage() {
        return continuationPage;
    }

    public int getContinuationOrdinal() {
        return continuationOrdinal;
    }

    public int getColCount() {
        return header.size();
    }

    /** 占位表（IMAGE_TABLE_NOT_PARSED）：无表头无行，flag 承载语义。 */
    public static DetectedTable placeholder(int pageNumber, String flag) {
        DetectedTable t = new DetectedTable(pageNumber);
        t.addFlag(flag);
        t.setConfidence(TablePatterns.CONFIDENCE_NA);
        return t;
    }
}
