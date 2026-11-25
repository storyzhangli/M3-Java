package com.zl.d42.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d42.document.table.PdfTableExtractor;
import com.zl.d42.document.table.model.DetectedTable;
import com.zl.d42.document.table.model.TableCell;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R02 专项：受控构造 PDF 夹具真值驱动逐格对账（口径=主迭代 P1 冒烟 9/9 同源规则，
 * 真值先行、禁以抽取结果反推真值；比对规则移植自 D42P1SelfTest）。
 *
 * <p>夹具性质：<b>synthetic/controlled fixture</b>（D42TableFixtureGenerator 受控生成，
 * PDFBox 绘制/Java2D 渲染，非真实设备手册——真实公开手册验证另见
 * docs/evidence/D41/R01/，两轨分开管理）。夹具完整性由 FIXTURE-SHA256.txt manifest 钉死。</p>
 */
class PdfTableFixtureTruthTest {

    static final String DIR = "/d42-table-fixtures/";
    static final ObjectMapper OM = new ObjectMapper();
    static JsonNode truth;
    static Map<String, byte[]> pdfs = new LinkedHashMap<>();
    static Map<String, PdfTableExtractor.Extraction> extractions = new LinkedHashMap<>();

    @BeforeAll
    static void loadFixtures() throws IOException {
        try (InputStream in = PdfTableFixtureTruthTest.class.getResourceAsStream(
                DIR + "fixture-truth.json")) {
            assertNotNull(in, "fixture-truth.json 缺失");
            truth = OM.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        PdfTableExtractor extractor = new PdfTableExtractor();
        List<String> seen = new ArrayList<>();
        truth.get("files").forEach(e -> seen.add(e.get("file").asText()));
        for (String file : seen.stream().distinct().toList()) {
            if (!file.endsWith(".pdf")) {
                continue;
            }
            byte[] bytes = readResource(file);
            pdfs.put(file, bytes);
            extractions.put(file, extractor.extract(bytes));
        }
    }

    static byte[] readResource(String name) throws IOException {
        try (InputStream in = PdfTableFixtureTruthTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, "夹具缺失: " + name);
            return in.readAllBytes();
        }
    }

    static List<DetectedTable> tablesOf(String file, int page) {
        return extractions.get(file).tables().stream()
                .filter(t -> t.getPageNumber() == page).toList();
    }

    static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    static JsonNode entryOf(String file, int page) {
        for (JsonNode e : truth.get("files")) {
            if (e.get("file").asText().equals(file) && e.get("page").asInt() == page) {
                return e;
            }
        }
        throw new IllegalArgumentException("truth entry 缺失: " + file + " p" + page);
    }

    /** 真值逐格核对（与 P1 selftest checkOneTable 同源规则）。 */
    static void checkCellwise(DetectedTable t, JsonNode entry, List<String> diffs) {
        if (!t.getHeader().equals(texts(entry.path("header")))) {
            diffs.add("header expect=" + texts(entry.path("header")) + " got=" + t.getHeader());
        }
        for (String flag : texts(entry.path("truthFlags"))) {
            if (!t.hasFlag(flag)) {
                diffs.add("missing truthFlag=" + flag);
            }
        }
        Map<Integer, Map<Integer, JsonNode>> byRow = new LinkedHashMap<>();
        for (JsonNode cell : entry.path("cells")) {
            int r = cell.get("row").asInt();
            if (r == 0) {
                continue;
            }
            byRow.computeIfAbsent(r, k -> new LinkedHashMap<>()).put(cell.get("col").asInt(), cell);
        }
        List<Integer> storedRows = new ArrayList<>();
        for (var r : byRow.entrySet()) {
            long nonBlank = r.getValue().values().stream()
                    .filter(c -> !c.get("missing").asBoolean()
                            && !c.get("text").asText().isBlank()).count();
            if (nonBlank > 1) {
                storedRows.add(r.getKey());
            }
        }
        if (t.getRows().size() != storedRows.size()) {
            diffs.add("data row count expect=" + storedRows.size() + " got=" + t.getRows().size());
        }
        for (int i = 0; i < Math.min(t.getRows().size(), storedRows.size()); i++) {
            List<TableCell> row = t.getRows().get(i);
            for (var c : byRow.get(storedRows.get(i)).entrySet()) {
                int col = c.getKey();
                JsonNode tc = c.getValue();
                if (col >= row.size()) {
                    diffs.add("r" + storedRows.get(i) + "c" + col + " missing in extraction");
                    continue;
                }
                TableCell cell = row.get(col);
                boolean expectNull = tc.get("missing").asBoolean()
                        || tc.get("text").asText().isBlank();
                if (expectNull) {
                    if (!cell.isNull()) {
                        diffs.add("r" + storedRows.get(i) + "c" + col
                                + " expect isNull got=" + cell.value());
                    }
                    continue;
                }
                String text = tc.get("text").asText().trim();
                String unit = tc.get("unit").isNull() ? null : tc.get("unit").asText();
                String expectValue = unit != null && text.endsWith(unit)
                        ? text.substring(0, text.length() - unit.length()).trim() : text;
                if (!Objects.equals(expectValue, cell.value())) {
                    diffs.add("r" + storedRows.get(i) + "c" + col + " value expect="
                            + expectValue + " got=" + cell.value());
                }
                if (!Objects.equals(unit, cell.unit())) {
                    diffs.add("r" + storedRows.get(i) + "c" + col + " unit expect="
                            + unit + " got=" + cell.unit());
                }
            }
        }
    }

    static void assertNoDiffs(List<String> diffs) {
        assertTrue(diffs.isEmpty(), String.join("; ", diffs));
    }

    @Test
    @DisplayName("有线故障代码表（4 列 4 行）：真值逐格一致，故障码连字符/前导零保真")
    void faultCodeRuledTable() {
        List<DetectedTable> ts = tablesOf("d42-fault-code.pdf", 1);
        assertEquals(1, ts.size());
        List<String> diffs = new ArrayList<>();
        checkCellwise(ts.get(0), entryOf("d42-fault-code.pdf", 1), diffs);
        assertNoDiffs(diffs);
        assertEquals("E-01", ts.get(0).getRows().get(0).get(0).value());
        assertEquals("code", ts.get(0).getRows().get(0).get(0).type());
    }

    @Test
    @DisplayName("参数规格表（含 12.5 mm / 380V / 1850 哨兵）：真值逐格一致")
    void paramSpecTable() {
        List<DetectedTable> ts = tablesOf("d42-param-spec.pdf", 1);
        assertEquals(1, ts.size());
        List<String> diffs = new ArrayList<>();
        checkCellwise(ts.get(0), entryOf("d42-param-spec.pdf", 1), diffs);
        assertNoDiffs(diffs);
    }

    @Test
    @DisplayName("有线合并单元格表（p1）+ 无线表（p2 同行列恢复）：MERGED_CELL 降级标记 + 逐格一致")
    void mergedAndNoRulingTables() {
        List<DetectedTable> p1 = tablesOf("d42-anomaly-merged.pdf", 1);
        List<DetectedTable> p2 = tablesOf("d42-anomaly-merged.pdf", 2);
        assertEquals(1, p1.size(), "p1=有线合并单元格表");
        assertEquals(1, p2.size(), "p2=无线表（坐标聚类不依赖画线）");

        List<String> diffs = new ArrayList<>();
        checkCellwise(p1.get(0), entryOf("d42-anomaly-merged.pdf", 1), diffs);
        checkCellwise(p2.get(0), entryOf("d42-anomaly-merged.pdf", 2), diffs);
        assertNoDiffs(diffs);
        assertTrue(p1.get(0).hasFlag("MERGED_CELL"),
                "合并异常必须以降级标记显式暴露，实际=" + p1.get(0).getFlags());
    }

    @Test
    @DisplayName("缺列表：缺格 isNull 占位、列位对齐不串列（truth missing 标记逐格验证）")
    void missingCellTable() {
        List<DetectedTable> ts = tablesOf("d42-anomaly-missing.pdf", 1);
        assertEquals(1, ts.size());
        List<String> diffs = new ArrayList<>();
        checkCellwise(ts.get(0), entryOf("d42-anomaly-missing.pdf", 1), diffs);
        assertNoDiffs(diffs);
        // 真值缺格 (row1,col2)/(row2,col1)/(row2,col2) 必须 isNull 且其后列不左移
        DetectedTable t = ts.get(0);
        assertTrue(t.getRows().get(0).get(2).isNull(), "r1c2 真值缺格必须 null 占位");
        assertEquals(4, t.getRows().get(0).size(), "缺列行保持表头列数（不串列）");
    }

    @Test
    @DisplayName("跨页续表：两页各 1 表、表头一致、第二页回链第一页且标 CONT_REPEAT_HEADER")
    void crossPageContinuation() {
        List<DetectedTable> p1 = tablesOf("d42-anomaly-crosspage.pdf", 1);
        List<DetectedTable> p2 = tablesOf("d42-anomaly-crosspage.pdf", 2);
        assertEquals(1, p1.size());
        assertEquals(1, p2.size());
        assertEquals(p1.get(0).getHeader(), p2.get(0).getHeader());
        assertTrue(p2.get(0).isContinuation(), "跨页同表头必须回链（收敛后仍成立的正面场景）");
        assertTrue(p2.get(0).hasFlag("CONT_REPEAT_HEADER"));
        List<String> diffs = new ArrayList<>();
        checkCellwise(p1.get(0), entryOf("d42-anomaly-crosspage.pdf", 1), diffs);
        checkCellwise(p2.get(0), entryOf("d42-anomaly-crosspage.pdf", 2), diffs);
        assertNoDiffs(diffs);
        // 续表数据不得把重复表头当数据行
        assertFalse(p2.get(0).getRows().stream()
                .anyMatch(r -> r.size() > 0 && "代码".equals(r.get(0).value())),
                "重复表头行不得进入数据行");
    }

    @Test
    @DisplayName("图片表（无 OCR）：1 张占位表，IMAGE_TABLE_NOT_PARSED + N/A + 零行，无虚构格")
    void imageTableHonestPlaceholder() {
        List<DetectedTable> ts = tablesOf("d42-image-table.pdf", 1);
        assertEquals(1, ts.size());
        DetectedTable t = ts.get(0);
        assertTrue(t.hasFlag("IMAGE_TABLE_NOT_PARSED"));
        assertEquals("N/A", t.getConfidence());
        assertTrue(t.getRows().isEmpty());
        assertTrue(t.getHeader().isEmpty());
    }

    @Test
    @DisplayName("无表格页：零检出，不伪造空表")
    void noTablePageYieldsZero() {
        assertTrue(tablesOf("d42-no-table.pdf", 1).isEmpty());
    }

    @Test
    @DisplayName("夹具完整性：全部 PDF 与 FIXTURE-SHA256.txt manifest 一致（受控夹具防篡改自证）")
    void fixtureManifestIntegrity() throws Exception {
        byte[] manifest;
        try (InputStream in = PdfTableFixtureTruthTest.class.getResourceAsStream(
                DIR + "FIXTURE-SHA256.txt")) {
            assertNotNull(in);
            manifest = in.readAllBytes();
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : new String(manifest, StandardCharsets.UTF_8)
                .replace("\r", "").split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s{2}");
            expected.put(parts[1], parts[0]);
        }
        assertFalse(expected.isEmpty());
        for (Map.Entry<String, String> e : expected.entrySet()) {
            byte[] bytes = readResource(e.getKey());
            assertEquals(e.getValue(), HexFormat.of().formatHex(md.digest(bytes)),
                    "夹具哈希漂移: " + e.getKey());
        }
    }
}
