package com.zl.d42.table;

import com.zl.d42.document.table.PdfTableExtractor;
import com.zl.d42.document.table.TextTableExtractor;
import com.zl.d42.document.table.model.DetectedTable;
import com.zl.d42.document.table.model.TableCell;
import com.zl.d42.document.table.model.TablePatterns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R02 专项：文本表提取器（md 管道表 / tab 分隔表 / 缺列补空 / 续表反例）。
 * 夹具=受控构造（synthetic/controlled fixture，src/test/resources/d42-table-fixtures/）。
 */
class TextTableExtractorTest {

    private final TextTableExtractor extractor = new TextTableExtractor();

    private static List<String> fixtureLines(String name) throws IOException {
        try (InputStream in = TextTableExtractorTest.class.getResourceAsStream(
                "/d42-table-fixtures/" + name)) {
            assertNotNull(in, "夹具缺失: " + name);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return List.of(text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));
        }
    }

    @Test
    @DisplayName("md 管道表：4 列 3 数据行、空寿命格 isNull、N·m 复合单位整体 string、20000 h 拆单位")
    void pipeTableStructureAndCells() throws IOException {
        List<DetectedTable> tables = extractor.extract(1, fixtureLines("d42-pipe-table.md"));
        assertEquals(1, tables.size());
        DetectedTable t = tables.get(0);
        assertEquals(List.of("部件", "型号", "额定扭矩", "寿命"), t.getHeader());
        assertEquals(3, t.getRows().size());

        // 数据行 0：减速机 | RV-110 | 1100 N·m | 20000 h
        List<TableCell> r0 = t.getRows().get(0);
        assertEquals("RV-110", r0.get(1).value());
        assertEquals("code", r0.get(1).type());
        // "1100 N·m"：·不在冻结单位字符类内 → 整体留 string（KP4 口径，不拆散）
        assertEquals("1100 N·m", r0.get(2).value());
        assertEquals("string", r0.get(2).type());
        assertEquals("20000", r0.get(3).value());
        assertEquals("h", r0.get(3).unit());

        // 数据行 1：联轴器寿命空 → isNull 占位且不串列
        List<TableCell> r1 = t.getRows().get(1);
        assertEquals(4, r1.size());
        assertTrue(r1.get(3).isNull(), "空单元格必须 null 占位");
        assertNull(r1.get(3).value());

        // 数据行 2：待复测 → string
        assertEquals("待复测", t.getRows().get(2).get(2).value());
        assertEquals("15000", t.getRows().get(2).get(3).value());
    }

    @Test
    @DisplayName("tab 分隔表：首行表头同构行数据，列数与值恢复")
    void tabSeparatedTable() {
        List<String> lines = List.of(
                "代码\t项目\t周期(月)",
                "PM-01\t主轴润滑\t6",
                "PM-02\t刀库更换\t12");
        List<DetectedTable> tables = extractor.extract(1, lines);
        assertEquals(1, tables.size());
        DetectedTable t = tables.get(0);
        assertEquals(List.of("代码", "项目", "周期(月)"), t.getHeader());
        assertEquals(2, t.getRows().size());
        assertEquals("PM-01", t.getRows().get(0).get(0).value());
        assertEquals("6", t.getRows().get(0).get(2).value());
        assertEquals("12", t.getRows().get(1).get(2).value());
    }

    @Test
    @DisplayName("缺列补空不串列：数据行比表头少列时按列位置补 null 并标 COLUMN_MISALIGNMENT")
    void shortRowPadsNullsWithoutShifting() {
        List<String> lines = List.of(
                "| A | B | C | D |",
                "| --- | --- | --- | --- |",
                "| 1 | 2 | 3 | 4 |",
                "| 5 | 6 |",
                "| 7 | 8 | 9 | 10 |");
        List<DetectedTable> tables = extractor.extract(1, lines);
        assertEquals(1, tables.size());
        DetectedTable t = tables.get(0);
        assertTrue(t.hasFlag(TablePatterns.FLAG_COLUMN_MISALIGNMENT));
        List<TableCell> shortRow = t.getRows().get(1);
        assertEquals(4, shortRow.size(), "缺列行必须补齐到表头列数（不串列）");
        assertEquals("5", shortRow.get(0).value());
        assertEquals("6", shortRow.get(1).value());
        assertTrue(shortRow.get(2).isNull());
        assertTrue(shortRow.get(3).isNull());
        // 后续完整行不受影响
        assertEquals("10", t.getRows().get(2).get(3).value());
    }

    @Test
    @DisplayName("反例（R02 红线）：同页两张独立同表头表，仅表头相同不得误认续表")
    void samePageSameHeaderMustNotLink() throws IOException {
        List<String> lines = fixtureLines("d42-pipe-continuation.md");
        List<DetectedTable> tables = new ArrayList<>(extractor.extract(1, lines));
        assertEquals(2, tables.size(), "同页两张管道表都应检出");
        for (DetectedTable t : tables) {
            assertEquals(List.of("编号", "名称", "数量"), t.getHeader());
        }
        PdfTableExtractor.linkContinuations(tables);
        for (DetectedTable t : tables) {
            assertFalse(t.isContinuation(),
                    "同页同表头属证据不足场景：宁可不建立续表关系（R01 整改收敛口径）");
            assertFalse(t.hasFlag(TablePatterns.FLAG_CONT_REPEAT_HEADER));
        }
    }

    @Test
    @DisplayName("正例：跨页重复表头 → 续表回链 + CONT_REPEAT_HEADER 标记")
    void crossPageSameHeaderLinks() {
        List<String> p1 = List.of(
                "| 代码 | 参数 |",
                "| --- | --- |",
                "| K1 | 10 |");
        List<String> p2 = List.of(
                "| 代码 | 参数 |",
                "| --- | --- |",
                "| K2 | 20 |");
        List<DetectedTable> tables = new ArrayList<>();
        tables.addAll(extractor.extract(1, p1));
        tables.addAll(extractor.extract(2, p2));
        PdfTableExtractor.linkContinuations(tables);
        assertEquals(2, tables.size());
        assertTrue(tables.get(1).isContinuation(), "跨页同表头必须回链");
        assertEquals(0, tables.get(1).getContinuationOrdinal());
        assertTrue(tables.get(1).hasFlag(TablePatterns.FLAG_CONT_REPEAT_HEADER));
        // 续表数据行不得把重复表头当数据
        assertEquals(1, tables.get(1).getRows().size());
        assertEquals("K2", tables.get(1).getRows().get(0).get(0).value());
    }

    @Test
    @DisplayName("无表格页不伪造：纯散文文本零检出")
    void prosePageYieldsNoTables() {
        List<String> lines = List.of("这是一段普通说明文字。", "没有任何表格结构。");
        assertTrue(extractor.extract(1, lines).isEmpty());
    }
}
