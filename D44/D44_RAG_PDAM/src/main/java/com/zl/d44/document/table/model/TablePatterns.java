package com.zl.d44.document.table.model;

import java.util.regex.Pattern;

/**
 * KP4 冻结正则/阈值集中定义（实现与测试一律引用本类，禁私改口径）。
 *
 * <p>阈值依据 KP1 坐标探针实证（97 真值格 0.00pt 偏差）与
 * {@code docs/evidence/D44/probe/table-params.json} 冻结文件。</p>
 */
public final class TablePatterns {

    /**
     * 数值+单位（空格分隔形态）：{@code 12.5 mm}/{@code ±0.01 mm}/{@code 3,500 rpm}/{@code 3 年}。
     * 单位限单 token（字母/希腊/度数/百分号/斜杠/CJK 量词），复合值
     * （如 {@code -20℃ to 60℃}、{@code 3.7-4.2 kW}）整体留 string（不拆散不误切）。
     */
    public static final Pattern NUM_SPACED_UNIT = Pattern.compile(
            "^([-+\u00b1]?[0-9][0-9,]*(\\.[0-9]+)?)[ \\t]+([A-Za-z\u00b5\u03a9\u00b0%/\\u4e00-\\u9fff]+)$");

    /** 数值+单位（紧贴形态）：{@code 380V}/{@code 2.5mm}/{@code 85%}；含 - 的日期串天然不匹配。 */
    public static final Pattern NUM_TIGHT_UNIT = Pattern.compile(
            "^([-+\u00b1]?[0-9][0-9,]*\\.?[0-9]*)([A-Za-z\u00b5\u03a9\u00b0%/]+)$");

    /** 纯数值（无单位哨兵，如整机重量 1850）。 */
    public static final Pattern PURE_NUMBER = Pattern.compile("^[-+\u00b1]?[0-9][0-9,]*(\\.[0-9]+)?$");

    /** 编码样式列：E-01 / SP-102 / RV-110 / H-03。 */
    public static final Pattern CODE_STYLE = Pattern.compile("^[A-Z][A-Z0-9]*(-[A-Z0-9]+)+$");

    /** md 管道表分隔行：{@code | --- | :---: |}。 */
    public static final Pattern PIPE_SEPARATOR = Pattern.compile("^\\|?\\s*:?-{2,}[^\\n]*$");

    // ---- 几何阈值（pt，KP4 冻结）----
    /** 行聚类 y 容差。 */
    public static final float ROW_TOL = 3.0f;
    /** 行内断格的相邻字形 x 间隙。 */
    public static final float COL_GAP = 25.0f;
    /** 跨行列锚点归并容差。 */
    public static final float COL_ANCHOR_TOL = 6.0f;
    /** 表格最小行数（含表头）。 */
    public static final int MIN_TABLE_ROWS = 2;
    /** 表格最小列数。 */
    public static final int MIN_TABLE_COLS = 2;
    /** 偏离众数列数的行占比超过该值 → LOW_CONFIDENCE。 */
    public static final double LOW_CONF_SHARE = 0.3;
    /** 图片表占位判定：页含 Image XObject ∧ 零表格 ∧ 文本行数不超过该值。 */
    public static final int IMAGE_PAGE_MAX_TEXT_LINES = 3;

    // ---- 退化/检出码表（KP4 冻结，detect_flags 逗号分隔）----
    public static final String FLAG_MERGED_CELL = "MERGED_CELL";
    public static final String FLAG_COLUMN_MISALIGNMENT = "COLUMN_MISALIGNMENT";
    public static final String FLAG_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String FLAG_CONT_REPEAT_HEADER = "CONT_REPEAT_HEADER";
    public static final String FLAG_IMAGE_TABLE_NOT_PARSED = "IMAGE_TABLE_NOT_PARSED";
    public static final String FLAG_WORD_NOT_SUPPORTED = "WORD_NOT_SUPPORTED";
    public static final String FLAG_NEAR_EMPTY_ROW = "NEAR_EMPTY_ROW";

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_LOW = "LOW";
    public static final String CONFIDENCE_NA = "N/A";

    /** 水平空白归一（与 TextCleaner.normalizeHorizontalWhitespace 同规则，内联避免跨包耦合）。 */
    public static String normalizeLine(String line) {
        if (line == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(line.length());
        boolean pendingSpace = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                pendingSpace = sb.length() > 0;
            } else {
                if (pendingSpace) {
                    sb.append(' ');
                    pendingSpace = false;
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private TablePatterns() {
    }
}
