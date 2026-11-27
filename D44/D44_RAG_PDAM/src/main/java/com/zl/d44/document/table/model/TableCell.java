package com.zl.d44.document.table.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 类型化表格单元格（KP4 cellSchema 冻结）：{@code {value, unit, type, isNull}} 四字段，
 * 单位与数值同记录不拆散（D10）；缺失单元格以 {@link #missing()} null 占位、不串列。
 *
 * <p>{@link JsonProperty} 显式钉死 JSON 键名：Jackson 对 boolean 的 is 前缀组件存在
 * 属性名折叠行为，不钉死则 cells_json 契约与 record_hash 不稳定（H4 对账前提）。</p>
 *
 * @param value 归一化文本值；缺格为 {@code null}
 * @param unit  单位（正则与数值同格抽取）；无单位/非数值为 {@code null}
 * @param type  number / code / string；缺格为 {@code null}
 * @param isNull 缺格（真值 missing）标记；空串单元格同样 isNull=true
 */
public record TableCell(
        @JsonProperty("value") String value,
        @JsonProperty("unit") String unit,
        @JsonProperty("type") String type,
        @JsonProperty("isNull") boolean isNull) {

    /** 缺格占位（null 不串列，H4 不静默错配）。 */
    public static TableCell missing() {
        return new TableCell(null, null, null, true);
    }

    /**
     * 按 KP4 冻结正则类型化：数值（可带单位）→ number；编码样式
     * {@code ^[A-Z][A-Z0-9]*(-[A-Z0-9]+)+$} → code；其余 string。
     * 空文本 → 缺格。
     */
    public static TableCell typeOf(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return missing();
        }
        String text = rawText.trim();
        // 单位两形态（KP4 修正）：空格分隔单 token 单位 / 紧贴字母单位；
        // 复合区间值与日期串不匹配任一形态 → 整体留 string（不拆散、不误切）。
        var spaced = TablePatterns.NUM_SPACED_UNIT.matcher(text);
        if (spaced.matches()) {
            return new TableCell(spaced.group(1), spaced.group(3), "number", false);
        }
        var tight = TablePatterns.NUM_TIGHT_UNIT.matcher(text);
        if (tight.matches()) {
            return new TableCell(tight.group(1), tight.group(2), "number", false);
        }
        if (TablePatterns.PURE_NUMBER.matcher(text).matches()) {
            return new TableCell(text, null, "number", false);
        }
        if (TablePatterns.CODE_STYLE.matcher(text).matches()) {
            return new TableCell(text, null, "code", false);
        }
        return new TableCell(text, null, "string", false);
    }
}
