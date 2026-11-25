package com.zl.d41.document.table;

import com.zl.d41.document.table.model.DetectedTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D41-R03 解析整改机制测试（T1–T5 先红后绿）：合成 Line/Span 输入直接驱动
 * 包内主路径（refineLine → detectTables → mergeAdjacentFragments），
 * 坐标参数取自真实手册 p396–p398 spans dump（docs\temp_docs\D41\r03\out\），
 * 场景与 error-list E1–E5 一一对应。不动 TableView/TableCell/DetectedTable 契约。
 */
class PdfTableR03MechTest {

    // ---------- 构造工具 ----------

    /** 单字形文本（等宽近似 10pt/字，CJK 20pt——测试只需 gap 逻辑，不依赖精确宽度）。 */
    private static List<PdfTableExtractor.Glyph> glyphsAt(float x, String text, double charW) {
        List<PdfTableExtractor.Glyph> gs = new ArrayList<>();
        float cx = x;
        for (char c : text.toCharArray()) {
            double w = c > 0x2E00 ? charW * 2 : charW; // CJK/□ 宽
            gs.add(new PdfTableExtractor.Glyph(cx, 0, (float) w, String.valueOf(c)));
            cx += (float) w;
        }
        return gs;
    }

    private static PdfTableExtractor.Span span(float x, String text, double charW) {
        List<PdfTableExtractor.Glyph> gs = glyphsAt(x, text, charW);
        float end = gs.get(gs.size() - 1).x() + gs.get(gs.size() - 1).width();
        return new PdfTableExtractor.Span(x, end - x, text, gs);
    }

    private static PdfTableExtractor.Line line(float y, PdfTableExtractor.Span... spans) {
        return new PdfTableExtractor.Line(y, List.of(spans));
    }

    private static List<String> pageLines(int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add("");
        }
        return l;
    }

    /** 两 span 间隙 < COL_GAP(25) 即"粘连"：把后续文本字形紧贴前段右缘排布。 */
    private static PdfTableExtractor.Span stickySpan(float x, String first, String glue, double charW) {
        List<PdfTableExtractor.Glyph> gs = new ArrayList<>(glyphsAt(x, first, charW));
        float end = gs.get(gs.size() - 1).x() + gs.get(gs.size() - 1).width();
        // glue 起点距 first 右缘 gap=8（<25 不自然断格，但含空格字形分界可切）
        float gx = (glue.startsWith(" ")) ? end + 8 : end;
        for (char c : glue.toCharArray()) {
            double w = c > 0x2E00 ? charW * 2 : charW;
            if (c == ' ') {
                w = 4;
            }
            gs.add(new PdfTableExtractor.Glyph(gx, 0, (float) w, String.valueOf(c)));
            gx += (float) w;
        }
        String text = first + glue;
        return new PdfTableExtractor.Span(x, gx - x, text, gs);
    }

    // ---------- T1：型号表头巨型 span 分裂 + 右侧列不再丢失 ----------

    @Test
    @DisplayName("T1-E1: 表头粘连型号串分裂为独立列，右侧型号列不整体丢失")
    void t1ModelHeaderGiantSpanSplit() {
        // p397 实测形态：Model + 一个巨型 span（型号串内 gap<COL_GAP）
        var l0 = line(421.1f,
                span(84.8f, "Model", 9),
                span(155.3f, "G11303□SE11305□SG11306□S", 9)); // 粘连三列（无空格）
        var l1 = line(439.3f,
                span(93.5f, "LC", 9), span(176.2f, "130", 9),
                span(254.8f, "130", 9), span(333.3f, "130", 9));
        var l2 = line(457.6f,
                span(94.1f, "LZ", 9), span(182.9f, "9", 9),
                span(261.4f, "9", 9), span(340.0f, "9", 9));
        var l3 = line(475.9f,
                span(93.8f, "LA", 9), span(176.2f, "145", 9),
                span(254.8f, "145", 9), span(333.3f, "145", 9));

        List<DetectedTable> tables =
                PdfTableExtractor.detectTables(1, List.of(l0, l1, l2, l3), pageLines(10));
        assertEquals(1, tables.size());
        DetectedTable t = tables.get(0);
        // 表头含三个型号列名（整改前：巨型 span 整体一格 → 右侧列丢失）
        assertTrue(t.getHeader().contains("G11303□S"), "表头应含 G11303□S: " + t.getHeader());
        assertTrue(t.getHeader().contains("E11305□S"), "表头应含 E11305□S: " + t.getHeader());
        assertTrue(t.getHeader().contains("G11306□S"), "表头应含 G11306□S(右侧列不丢): " + t.getHeader());
        // 每数据行 3 个数值各归各列
        var lc = t.getRows().get(0);
        long numbers = lc.stream().filter(c -> !c.isNull() && "number".equals(c.type())).count();
        assertEquals(3, numbers, "LC 行 3 数值应分列：\n" + lc);
    }

    // ---------- T2：表头巨型 span 使表区自然含表头，首数据行不升格 ----------

    @Test
    @DisplayName("T2-E2: 表头行分裂后进入表区，首数据行不再升格为表头")
    void t2HeaderNotPromotedFromDataRow() {
        // p398 实测形态：表头=单巨型 span（分裂前 spans=1 → 表区从数据行起 → LC 升格）
        var l0 = line(425.9f,
                span(78.4f, "Model E11310□S E11315□S C11020□S", 9));
        var l1 = line(444.2f, span(87.1f, "LC", 9), span(158.0f, "130", 9), span(225.7f, "130", 9));
        var l2 = line(462.4f, span(87.8f, "LZ", 9), span(164.7f, "9", 9), span(232.3f, "9", 9));
        var l3 = line(480.7f, span(87.4f, "LA", 9), span(158.0f, "145", 9), span(225.7f, "145", 9));

        List<DetectedTable> tables =
                PdfTableExtractor.detectTables(1, List.of(l0, l1, l2, l3), pageLines(10));
        assertEquals(1, tables.size());
        DetectedTable t = tables.get(0);
        assertEquals("Model", t.getHeader().get(0), "表头首列应为 Model（非首数据行 LC 升格）");
        assertTrue(t.getHeader().contains("E11310□S"));
        var firstRow = t.getRows().get(0);
        assertEquals("LC", firstRow.stream().filter(c -> !c.isNull())
                .findFirst().orElseThrow().value(), "首数据行必须是 LC 行且未被吞为表头");
        assertEquals(3, t.getRows().size(), "3 个数据行全部归表");
    }

    // ---------- T3：长标签行 x 漂移按表头锚点区间回拉 ----------

    @Test
    @DisplayName("T3-E3: 长标签行(x 左漂)仍归标签列，数值列不被挤偏")
    void t3LongLabelPullback() {
        var l0 = line(432.1f,
                span(93.5f, "Model", 9), span(180.2f, "C10602□S", 9), span(274.2f, "C10604□S", 9));
        var l1 = line(450.4f,
                span(102.1f, "LC", 9), span(204.6f, "60", 9), span(298.5f, "60", 9));
        // LL 长标签 x=67.1 左漂 26pt（E3 实测形态），数值 x 正常
        var l2 = line(541.9f,
                span(67.1f, "LL（不帶剎車）", 18), span(196.3f, "105.5", 9), span(290.2f, "130.7", 9));

        List<DetectedTable> tables =
                PdfTableExtractor.detectTables(1, List.of(l0, l1, l2), pageLines(10));
        assertEquals(1, tables.size());
        DetectedTable t = tables.get(0);
        var ll = t.getRows().get(1);
        assertEquals(0, indexOfValue(ll, "LL（不帶剎車）"), "长标签必须落标签列(c0)");
        assertEquals(1, indexOfValue(ll, "105.5"), "首数值列应=C10602□S 列");
        assertEquals(2, indexOfValue(ll, "130.7"), "次数值列应=C10604□S 列");
    }

    private static int indexOfValue(List<com.zl.d41.document.table.model.TableCell> row, String v) {
        for (int i = 0; i < row.size(); i++) {
            if (v.equals(row.get(i).value())) {
                return i;
            }
        }
        return -1;
    }

    // ---------- T4：参数块碎片按 lineIndex 邻接合并 ----------

    @Test
    @DisplayName("T4-E5: 同页同列数邻接单行碎片表合并为一张，行可追溯 lineIndex")
    void t4AdjacentFragmentMerge() {
        List<DetectedTable> frags = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DetectedTable t = new DetectedTable(7);
            // 碎片形态：伪表头=参数行本身，仅 1 数据行，lineIndex 邻接 20..25
            t.getHeader().add("P" + i);
            t.getHeader().add(String.valueOf(10 + i));
            t.addRegionLine("P" + i + " " + (10 + i), 2 * i + 20);
            t.addRegionLine("Q" + i + " " + (20 + i), 2 * i + 21);
            t.addDataRow(List.of(
                    com.zl.d41.document.table.model.TableCell.typeOf("Q" + i),
                    com.zl.d41.document.table.model.TableCell.typeOf(String.valueOf(20 + i))),
                    2 * i + 21);
            frags.add(t);
        }
        List<DetectedTable> merged = PdfTableExtractor.mergeAdjacentFragments(frags);
        assertEquals(1, merged.size(), "lineIndex 20..25 邻接碎片应合并");
        DetectedTable m = merged.get(0);
        // 语义：保留首表头；后续碎片伪表头降格为数据行——原始行零丢失
        assertEquals(5, m.getRows().size(), "3 碎片合并=1+2+2 行（伪表头降格不丢行）");
        assertEquals(List.of(20, 21, 22, 23, 24, 25), m.getLineIndexes(),
                "合并后全部原始 lineIndex 可追溯");
        assertEquals("P0", m.getHeader().get(0));
    }

    @Test
    @DisplayName("T4 防御: lineIndex 间隙大于阈值不合并（真独立表不误并）")
    void t4FarFragmentsNotMerged() {
        List<DetectedTable> frags = new ArrayList<>();
        for (int idx : new int[]{20, 26}) {
            DetectedTable t = new DetectedTable(7);
            t.getHeader().add("参数");
            t.getHeader().add("值");
            t.addRegionLine("x y", idx);
            t.addDataRow(List.of(
                    com.zl.d41.document.table.model.TableCell.typeOf("x"),
                    com.zl.d41.document.table.model.TableCell.typeOf("y")), idx);
            frags.add(t);
        }
        assertEquals(2, PdfTableExtractor.mergeAdjacentFragments(frags).size());
    }

    // ---------- T5：复合单元格"标签 数值"黏连分隔 ----------

    @Test
    @DisplayName("T5-E4: 'LL（不帶剎車） 147.5' 黏连 span 在空格分界切为标签+数值两格")
    void t5StickyLabelValueSplit() {
        var sticky = stickySpan(58.5f, "LL（不帶剎車）", " 147.5", 9);
        var l = line(530.9f, sticky, span(249.8f, "147.5", 9));
        PdfTableExtractor.Line refined = PdfTableExtractor.refineLine(l);
        assertEquals(3, refined.spans().size(), "黏连 span 应切出独立数值段: " + refined.spans());
        assertEquals("LL（不帶剎車）", refined.spans().get(0).text());
        assertEquals("147.5", refined.spans().get(1).text());
        // 数值段 x 必须取切点后字形真实 x（回拉列位的前提）
        assertEquals(249.8f, refined.spans().get(2).x(), 0.01f);
        assertTrue(refined.spans().get(1).x() > stickySpan(0f, "a", "", 1).x());
    }

    @Test
    @DisplayName("T5 防御: '5.5 mm' 数值+单位同记录不拆分（D10 契约）")
    void t5UnitPairNotSplit() {
        var s = stickySpan(100f, "5.5", " mm", 9);
        var l = line(1f, s, span(300f, "12", 9));
        PdfTableExtractor.Line refined = PdfTableExtractor.refineLine(l);
        assertEquals("5.5 mm", refined.spans().get(0).text(),
                "前缀为数值 → 空格后非数值段不切，单位同记录保持");
    }

    @Test
    @DisplayName("T5 防御: 型号串 'C11010ES' 与含连字符日期形态不切")
    void t5ModelAndDateNotSplit() {
        var l = line(1f,
                span(100f, "C11010ES", 9),
                span(200f, "2008-11-01", 9),
                span(300f, "80W/400W", 9));
        PdfTableExtractor.Line refined = PdfTableExtractor.refineLine(l);
        assertEquals(3, refined.spans().size());
        assertEquals("C11010ES", refined.spans().get(0).text());
        assertEquals("2008-11-01", refined.spans().get(1).text());
        assertEquals("80W/400W", refined.spans().get(2).text());
    }
}
