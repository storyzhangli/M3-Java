package com.zl.d43.table.fixtures;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;

/**
 * KP3 受控构造夹具生成器（来源标注：受控构造，PDFBox 绘制 / Java2D 渲染）。
 *
 * <p>产出（参数 1=输出目录）：d43-fault-code.pdf / d43-param-spec.pdf / d43-anomaly-merged.pdf /
 * d43-anomaly-crosspage.pdf / d43-anomaly-missing.pdf / d43-image-table.pdf / d43-no-table.pdf /
 * d43-pipe-table.md / d43-pipe-continuation.md + fixture-truth.json（坐标真值=对账唯一依据，
 * 由绘制参数直接生成，非抽取结果反推）。</p>
 *
 * <p>坐标系（KP4 冻结）：真值与绘制统一用"距顶 y"（=TextPosition.getYDirAdj() 同空间，
 * 文本基线），绘制时转 PDF 底上空间 H-y。列 x=单元格文本起点（列左缘+内边距）。</p>
 */
public final class D43TableFixtureGenerator {

    static final float W = 595f, H = 842f;            // A4 pt
    static final float TABLE_X = 72f, TOP_Y = 82f;    // TOP_Y=表头基线距顶
    static final float ROW_H = 22f, FONT = 10f;
    static final int TEXT_PAD = 4;
    static final float COL_W = 110f;                  // 末列右缘宽度

    record Cell(String text, float x, float yTop, int row, int col, String unit, boolean missing) {}
    record TableOf(String kind, String[] header, List<String[]> rows, List<Cell> cells,
                   List<Float> colX, boolean ruled, List<String> truthFlags) {}

    /** 合并/缺格竖线跳过集："row:colBoundary"（在该行不画 col 左缘竖线）。 */
    private static Set<String> vskip = new HashSet<>();

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);
        List<String> jsonParts = new ArrayList<>();

        // ---------- 1. 故障码表（有线 4 列） + 正文段（零差异哨兵可检索上下文） ----------
        vskip = new HashSet<>();
        try (PDDocument doc = new PDDocument()) {
            PDFont cn = cjkFont(doc);
            float[] colX = {TABLE_X, TABLE_X + 90, TABLE_X + 250, TABLE_X + 400};
            PDPage p = new PDPage(new PDRectangle(W, H));
            doc.addPage(p);
            PDPageContentStream c = new PDPageContentStream(doc, p);
            textOn(c, cn, TABLE_X, TOP_Y - 30, "故障代码表", 13f);
            TableOf t = drawTableOn(c, cn, "d43-fault-code", null,
                    new String[]{"故障代码", "故障名称", "含义说明", "处置动作"},
                    List.of(
                            new String[]{"E-01", "TEMP_HIGH", "主轴温度超过上限", "停机冷却并检查风扇"},
                            new String[]{"E-02", "OIL_PRESS", "润滑油压低于阈值", "补油并检查油泵"},
                            new String[]{"E-03", "VIB_ALARM", "振动幅值超限", "紧固地脚并动平衡"},
                            new String[]{"E-04", "CURR_OVER", "驱动电流过流", "检查负载与电缆"}),
                    colX, true);
            textOn(c, cn, TABLE_X, TOP_Y + (1 + 4) * ROW_H + 30,
                    "上述故障代码用于 CNC 冷却系统维护，出现 E-01 时应先执行处置动作再复位。");
            c.close();
            jsonParts.add(tableJson("d43-fault-code.pdf", 1, t));
            doc.save(outDir.resolve("d43-fault-code.pdf").toString());
        }

        // ---------- 2. 参数表（A1 单位混合；"1850" 为缺失单位哨兵；空备注=空串非缺格） ----------
        vskip = new HashSet<>();
        try (PDDocument doc = new PDDocument()) {
            PDFont cn = cjkFont(doc);
            float[] colX = {TABLE_X, TABLE_X + 130, TABLE_X + 280, TABLE_X + 400};
            PDPage p = new PDPage(new PDRectangle(W, H));
            doc.addPage(p);
            PDPageContentStream c = new PDPageContentStream(doc, p);
            textOn(c, cn, TABLE_X, TOP_Y - 30, "技术参数表", 13f);
            TableOf t = drawTableOn(c, cn, "d43-param-spec", null,
                    new String[]{"参数名称", "数值", "公差", "备注"},
                    List.of(
                            new String[]{"主轴跳动", "12.5 mm", "±0.01 mm", "出厂基准"},
                            new String[]{"供电电压", "380V", "±5%", "AC 三相"},
                            new String[]{"额定转速", "3,500 rpm", "±0.5%", "连续负载"},
                            new String[]{"冷却流量", "24 L/min", "±2 L/min", "夏季上限"},
                            new String[]{"整机重量", "1850", "—", "缺失单位哨兵"}),
                    colX, true);
            c.close();
            jsonParts.add(tableJson("d43-param-spec.pdf", 1, t));
            doc.save(outDir.resolve("d43-param-spec.pdf").toString());
        }

        // ---------- 3. A2 合并单元格（跳过竖线）+ 无线表（两页） ----------
        vskip = Set.of("2:1"); // 数据行1（全表 rowIdx=2）跳过 colX[1] 竖线 → col0 文本横跨 0-1 列
        try (PDDocument doc = new PDDocument()) {
            PDFont cn = cjkFont(doc);
            float[] colX = {TABLE_X, TABLE_X + 120, TABLE_X + 260, TABLE_X + 400};
            PDPage p1 = new PDPage(new PDRectangle(W, H));
            doc.addPage(p1);
            PDPageContentStream c1 = new PDPageContentStream(doc, p1);
            textOn(c1, cn, TABLE_X, TOP_Y - 30, "备件对照表（第 2 行编号+名称合并为一格）", 12f);
            TableOf merged = drawTableOn(c1, cn, "d43-merged", null,
                    new String[]{"备件编号", "名称", "规格", "周期"},
                    List.of(
                            new String[]{"SP-101", "密封圈", "OD 42 mm", "6 个月"},
                            new String[]{"SP-102 通用套件", null, "含 3 种规格", "12 个月"},
                            new String[]{"SP-103", "滤芯", "Φ 80 mm", "3 个月"}),
                    colX, true);
            c1.close();
            jsonParts.add(tableJson("d43-anomaly-merged.pdf", 1, merged));
            vskip = new HashSet<>();
            PDPage p2 = new PDPage(new PDRectangle(W, H));
            doc.addPage(p2);
            PDPageContentStream c2 = new PDPageContentStream(doc, p2);
            textOn(c2, cn, TABLE_X, TOP_Y - 30, "无线保养表（纯坐标列）", 12f);
            TableOf noRuling = drawTableOn(c2, cn, "d43-noruling", null,
                    new String[]{"项目", "标准", "周期"},
                    List.of(
                            new String[]{"导轨润滑", "每 8 小时", "自动"},
                            new String[]{"风道清扫", "压差 >50 Pa", "按需"}),
                    new float[]{TABLE_X, TABLE_X + 130, TABLE_X + 300}, false);
            c2.close();
            jsonParts.add(tableJson("d43-anomaly-merged.pdf", 2, noRuling));
            doc.save(outDir.resolve("d43-anomaly-merged.pdf").toString());
        }

        // ---------- 4. A3 跨页续表（第 2 页重复表头） ----------
        vskip = new HashSet<>();
        try (PDDocument doc = new PDDocument()) {
            PDFont cn = cjkFont(doc);
            String[] header = {"代码", "子系统", "等级"};
            float[] colX = {TABLE_X, TABLE_X + 130, TABLE_X + 300};
            PDPage p1 = new PDPage(new PDRectangle(W, H));
            doc.addPage(p1);
            PDPageContentStream c1 = new PDPageContentStream(doc, p1);
            textOn(c1, cn, TABLE_X, TOP_Y - 30, "液压系统告警表（跨页）", 12f);
            TableOf pa = drawTableOn(c1, cn, "d43-crosspage-A", null, header,
                    List.of(new String[]{"H-01", "泵源", "P1"}, new String[]{"H-02", "阀组", "P2"}),
                    colX, true);
            c1.close();
            jsonParts.add(tableJson("d43-anomaly-crosspage.pdf", 1, pa));
            PDPage p2 = new PDPage(new PDRectangle(W, H));
            doc.addPage(p2);
            PDPageContentStream c2 = new PDPageContentStream(doc, p2);
            textOn(c2, cn, TABLE_X, TOP_Y - 30, "（续）", 11f);
            TableOf pb = drawTableOn(c2, cn, "d43-crosspage-B", null, header,
                    List.of(new String[]{"H-03", "蓄能", "P1"}, new String[]{"H-04", "管路", "P3"}),
                    colX, true);
            c2.close();
            jsonParts.add(tableJson("d43-anomaly-crosspage.pdf", 2,
                    withFlag(pb, "CONT_REPEAT_HEADER")));
            doc.save(outDir.resolve("d43-anomaly-crosspage.pdf").toString());
        }

        // ---------- 5. A4 缺失单元格（M-1 缺 col2；M-2 仅 col0 → 近空行哨兵） ----------
        vskip = new HashSet<>();
        try (PDDocument doc = new PDDocument()) {
            PDFont cn = cjkFont(doc);
            float[] colX = {TABLE_X, TABLE_X + 120, TABLE_X + 250, TABLE_X + 380};
            PDPage p = new PDPage(new PDRectangle(W, H));
            doc.addPage(p);
            PDPageContentStream c = new PDPageContentStream(doc, p);
            textOn(c, cn, TABLE_X, TOP_Y - 30, "点检缺项表", 13f);
            TableOf t = drawTableOn(c, cn, "d43-missing", null,
                    new String[]{"点位", "温度", "振动", "备注"},
                    List.of(
                            new String[]{"M-1", "62 C", null, "振动未测"},
                            new String[]{"M-2", null, null, null},
                            new String[]{"M-3", "58 C", "0.8 mm/s", ""}),
                    colX, true);
            c.close();
            jsonParts.add(tableJson("d43-anomaly-missing.pdf", 1, t));
            doc.save(outDir.resolve("d43-anomaly-missing.pdf").toString());
        }

        // ---------- 6. A5 Java2D 图片表（无文本层） ----------
        try (PDDocument doc = new PDDocument()) {
            PDFont cn = cjkFont(doc);
            PDPage p = new PDPage(new PDRectangle(W, H));
            doc.addPage(p);
            PDPageContentStream c = new PDPageContentStream(doc, p);
            textOn(c, cn, TABLE_X, TOP_Y - 30, "下表为图片版传感器清单（无文本层）", 12f);
            BufferedImage img = renderImageTable();
            PDImageXObject xo = LosslessFactory.createFromImage(doc, img);
            c.drawImage(xo, TABLE_X, H - (TOP_Y + 20) - 175, 420, 175); // 顶边=TOP_Y+20（距顶）
            c.close();
            jsonParts.add(imageTableJson());
            doc.save(outDir.resolve("d43-image-table.pdf").toString());
        }

        // ---------- 7. 零差异对照（纯正文无表） ----------
        try (PDDocument doc = new PDDocument()) {
            PDFont cn = cjkFont(doc);
            PDPage p = new PDPage(new PDRectangle(W, H));
            doc.addPage(p);
            PDPageContentStream c = new PDPageContentStream(doc, p);
            textOn(c, cn, TABLE_X, TOP_Y, "冷却系统日常维护说明", 14f);
            textOn(c, cn, TABLE_X, TOP_Y + 30, "每班检查液位，液位低于下限时应补足至中线位置。");
            textOn(c, cn, TABLE_X, TOP_Y + 52, "每周清洁风道滤网，压差超过 50 Pa 时更换滤芯。");
            textOn(c, cn, TABLE_X, TOP_Y + 74, "每月校验温度传感器，偏差超过 2 C 时更换。");
            c.close();
            doc.save(outDir.resolve("d43-no-table.pdf").toString());
            jsonParts.add("    {\"file\":\"d43-no-table.pdf\",\"page\":1,\"tables\":[],\"kind\":\"d43-no-table\"}");
        }

        // ---------- 8. md 管道表夹具 ----------
        String md = """
                # 传动部件参数表

                | 部件 | 型号 | 额定扭矩 | 寿命 |
                | --- | --- | --- | --- |
                | 减速机 | RV-110 | 1100 N·m | 20000 h |
                | 联轴器 | CX-25 | 25 N·m |  |
                | 丝杠 | BF-1610 | 待复测 | 15000 h |

                正文说明：以上参数为出厂标定值，装配前须复核。
                """;
        Files.writeString(outDir.resolve("d43-pipe-table.md"), md, StandardCharsets.UTF_8);
        String mdCont = """
                # 电气备件清单

                | 编号 | 名称 | 数量 |
                | --- | --- | --- |
                | EL-01 | 接触器 | 2 |
                | EL-02 | 热继电器 | 4 |

                | 编号 | 名称 | 数量 |
                | --- | --- | --- |
                | EL-03 | 熔断器 | 10 |

                续表说明：第二张表沿用同一表头，模拟重复标题场景。
                """;
        Files.writeString(outDir.resolve("d43-pipe-continuation.md"), mdCont, StandardCharsets.UTF_8);

        String json = "{\n  \"source\": \"受控构造（PDFBox 绘制 / Java2D 渲染，D43TableFixtureGen）\",\n"
                + "  \"coordinateSpace\": \"top-origin y = TextPosition.getYDirAdj() 同空间（文本基线距顶）；x=单元格文本起点\",\n"
                + "  \"geometry\": {\"tableX\": " + TABLE_X + ", \"headerBaselineTop\": " + TOP_Y
                + ", \"rowH\": " + ROW_H + ", \"fontSize\": " + FONT + ", \"pageW\": " + W
                + ", \"pageH\": " + H + "},\n"
                + "  \"files\": [\n" + String.join(",\n", jsonParts) + "\n  ]\n}\n";
        Files.writeString(outDir.resolve("fixture-truth.json"), json, StandardCharsets.UTF_8);
        System.out.println("[OK] fixtures + fixture-truth.json -> " + outDir);
    }

    // ================= 绘制（真值与绘制同源） =================

    static PDFont cjkFont(PDDocument doc) throws IOException {
        File ttf = new File("C:\\Windows\\Fonts\\simhei.ttf");
        if (ttf.exists()) {
            return PDType0Font.load(doc, ttf);
        }
        File ttc = new File("C:\\Windows\\Fonts\\msyh.ttc");
        if (ttc.exists()) {
            TrueTypeCollection coll = new TrueTypeCollection(ttc);
            final TrueTypeFont[] holder = new TrueTypeFont[1];
            coll.processAllFonts(f -> { if (holder[0] == null) holder[0] = f; });
            return PDType0Font.load(doc, holder[0], true);
        }
        throw new IOException("NO_CJK_FONT");
    }

    static TableOf withFlag(TableOf t, String flag) {
        List<String> flags = new ArrayList<>(t.truthFlags());
        flags.add(flag);
        return new TableOf(t.kind(), t.header(), t.rows(), t.cells(), t.colX(), t.ruled(), flags);
    }

    /**
     * 画表并记录真值。行号 row：表头=0，数据行从 1 起。null 单元格=缺格（missing，不落文本，
     * 真值仍记录其名义位置供 null 占位对账）；空串 ""=有格无值（不参与坐标比对）。
     */
    static TableOf drawTableOn(PDPageContentStream c, PDFont cn, String kind, String subCaption,
                               String[] header, List<String[]> rows, float[] colX,
                               boolean ruled) throws IOException {
        List<Cell> cells = new ArrayList<>();
        int totalRows = rows.size() + 1;
        float bottom = TOP_Y + totalRows * ROW_H; // 表底距顶
        if (ruled) {
            float right = colX[colX.length - 1] + COL_W;
            c.setLineWidth(0.6f);
            for (int i = 0; i <= totalRows; i++) { // 横线（距顶→底上）
                float ly = TOP_Y + i * ROW_H;
                c.moveTo(TABLE_X, H - ly);
                c.lineTo(right, H - ly);
                c.stroke();
            }
            for (int ci = 0; ci < colX.length; ci++) { // 竖线逐行分段，vskip 命中行跳过
                float lx = colX[ci];
                for (int rowIdx = 0; rowIdx < totalRows; rowIdx++) {
                    if (vskip.contains(rowIdx + ":" + ci)) {
                        continue;
                    }
                    float segTop = TOP_Y + rowIdx * ROW_H;
                    c.moveTo(lx, H - segTop);
                    c.lineTo(lx, H - segTop - ROW_H);
                    c.stroke();
                }
                float lastX = colX[colX.length - 1] + COL_W;
                c.moveTo(lastX, H - TOP_Y);
                c.lineTo(lastX, H - bottom);
                c.stroke();
            }
        }
        // 表头基线
        float hy = TOP_Y + FONT + 5;
        for (int ci = 0; ci < header.length; ci++) {
            drawCell(c, cn, colX[ci] + TEXT_PAD, hy, header[ci]);
            cells.add(new Cell(header[ci], colX[ci] + TEXT_PAD, hy, 0, ci, null, false));
        }
        List<String[]> normalized = new ArrayList<>();
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] row = rows.get(ri);
            String[] fixed = new String[header.length];
            float ry = TOP_Y + (ri + 1) * ROW_H + FONT + 5;
            for (int ci = 0; ci < header.length; ci++) {
                String raw = ci < row.length ? row[ci] : null;
                fixed[ci] = raw;
                if (raw == null || raw.isEmpty()) {
                    cells.add(new Cell("", colX[ci] + TEXT_PAD, ry, ri + 1, ci, null, raw == null));
                    continue;
                }
                drawCell(c, cn, colX[ci] + TEXT_PAD, ry, raw);
                cells.add(new Cell(raw, colX[ci] + TEXT_PAD, ry, ri + 1, ci, extractUnit(raw), false));
            }
            normalized.add(fixed);
        }
        return new TableOf(kind, header, normalized, cells, List.of(box(colX)), ruled, List.of());
    }

    static void drawCell(PDPageContentStream c, PDFont cn, float xTop, float yTop, String t)
            throws IOException {
        c.beginText();
        c.setFont(cn, FONT);
        c.newLineAtOffset(xTop, H - yTop);
        c.showText(t);
        c.endText();
    }

    /**
     * 单位提取真值：<b>直接复用主抽取器 TablePatterns 的 SPACED/TIGHT 正则</b>。
     *
     * <p>迁移缺陷修复（D-EX5）：D41 原生成器此处用宽式 {@code (\S+)} 自定义正则，
     * 会把"1850"的个位"0"误判为单位（主抽取器实际 unit=null）；D41 冻结夹具为旧代
     * 产物未暴露该分歧。D43 再生夹具必须与主路径同源，否则 truth 自证失效。</p>
     */
    static String extractUnit(String raw) {
        var spaced = com.zl.d43.document.table.model.TablePatterns.NUM_SPACED_UNIT.matcher(raw);
        if (spaced.matches()) {
            return spaced.group(3);
        }
        var tight = com.zl.d43.document.table.model.TablePatterns.NUM_TIGHT_UNIT.matcher(raw);
        if (tight.matches()) {
            return tight.group(2);
        }
        return null;
    }

    static void textOn(PDPageContentStream c, PDFont cn, float x, float yFromTop, String t, float size)
            throws IOException {
        c.beginText();
        c.setFont(cn, size);
        c.newLineAtOffset(x, H - yFromTop);
        c.showText(t);
        c.endText();
    }

    static void textOn(PDPageContentStream c, PDFont cn, float x, float yFromTop, String t)
            throws IOException {
        textOn(c, cn, x, yFromTop, t, 10f);
    }

    static Float[] box(float[] a) {
        Float[] r = new Float[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i];
        }
        return r;
    }

    // ================= JSON（手写，零第三方依赖） =================

    static String tableJson(String file, int page, TableOf t) {
        StringBuilder sb = new StringBuilder();
        sb.append("    {\"file\":\"").append(file).append("\",\"page\":").append(page)
          .append(",\"kind\":\"").append(t.kind()).append("\",\"ruled\":").append(t.ruled());
        sb.append(",\"truthFlags\":[").append(joinQuoted(t.truthFlags())).append("]");
        sb.append(",\"header\":[").append(joinQuoted(t.header())).append("]");
        sb.append(",\"rows\":[");
        for (int i = 0; i < t.rows().size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(joinQuoted(t.rows().get(i)));
        }
        sb.append("],\"cells\":[");
        for (int i = 0; i < t.cells().size(); i++) {
            Cell cl = t.cells().get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"text\":\"").append(esc(cl.text())).append("\",\"x\":")
              .append(fmt(cl.x())).append(",\"y\":").append(fmt(cl.yTop()))
              .append(",\"row\":").append(cl.row()).append(",\"col\":").append(cl.col())
              .append(",\"unit\":").append(cl.unit() == null ? "null" : "\"" + esc(cl.unit()) + "\"")
              .append(",\"missing\":").append(cl.missing()).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static String imageTableJson() {
        return "    {\"file\":\"d43-image-table.pdf\",\"page\":1,\"kind\":\"d43-image-table\","
                + "\"truthFlags\":[\"IMAGE_TABLE_NOT_PARSED\"],"
                + "\"note\":\"表格为 Java2D 位图（无文本层）；主路径预期：文本层零表格行+页面含图片 XObject→标注 IMAGE_TABLE_NOT_PARSED；OCR 无引擎→NOT RUN，置信度 N/A\","
                + "\"imageContentRows\":[\"传感器|S-201|PT100\",\"温度|S-202|M12 电感\",\"接近|S-203|2048 ppr\"],"
                + "\"expectedTextCells\":[]}";
    }

    static String joinQuoted(Object arr) {
        String[] a = arr instanceof String[] s ? s : ((List<?>) arr).stream().map(String::valueOf).toArray(String[]::new);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(a[i] == null ? "null" : "\"" + esc(String.valueOf(a[i])) + "\"");
        }
        return sb.toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String fmt(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static BufferedImage renderImageTable() {
        int w = 840, h = 350;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        String[][] data = {{"传感器", "编号", "型号"},
                           {"温度", "S-201", "PT100"},
                           {"接近", "S-202", "M12 电感"},
                           {"编码器", "S-203", "2048 ppr"}};
        int[] colPx = {0, 280, 560};
        g.setFont(new Font("SansSerif", Font.PLAIN, 40));
        for (int r = 0; r < data.length; r++) {
            int y = 60 + r * 70;
            g.drawLine(0, y - 45, w, y - 45);
            g.drawLine(0, y + 25, w, y + 25);
            for (int c = 0; c < data[r].length; c++) {
                g.drawString(data[r][c], colPx[c] + 15, y);
                g.drawLine(colPx[c], y - 45, colPx[c], y + 25);
            }
            g.drawLine(w - 2, y - 45, w - 2, y + 25);
        }
        g.dispose();
        return img;
    }
}
