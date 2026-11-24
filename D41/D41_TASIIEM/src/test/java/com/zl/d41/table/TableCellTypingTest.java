package com.zl.d41.table;

import com.zl.d41.document.table.model.TableCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R02 专项：单元格类型化（KP4 冻结正则）——数值/单位组合、纯数字哨兵、
 * 编码样式、空值状态区分、复合值整体保留（不拆散不误切）。
 */
class TableCellTypingTest {

    @Test
    @DisplayName("空格分隔单位：12.5 mm / 3,500 rpm / ±0.01 mm 拆值与单位")
    void spacedUnits() {
        TableCell a = TableCell.typeOf("12.5 mm");
        org.junit.jupiter.api.Assertions.assertEquals("12.5", a.value());
        org.junit.jupiter.api.Assertions.assertEquals("mm", a.unit());
        org.junit.jupiter.api.Assertions.assertEquals("number", a.type());

        TableCell b = TableCell.typeOf("3,500 rpm");
        org.junit.jupiter.api.Assertions.assertEquals("3,500", b.value());
        org.junit.jupiter.api.Assertions.assertEquals("rpm", b.unit());

        TableCell c = TableCell.typeOf("±0.01 mm");
        org.junit.jupiter.api.Assertions.assertEquals("±0.01", c.value());
        org.junit.jupiter.api.Assertions.assertEquals("mm", c.unit());
    }

    @Test
    @DisplayName("紧贴单位：380V / 85% 拆值与单位")
    void tightUnits() {
        TableCell v = TableCell.typeOf("380V");
        org.junit.jupiter.api.Assertions.assertEquals("380", v.value());
        org.junit.jupiter.api.Assertions.assertEquals("V", v.unit());
        TableCell p = TableCell.typeOf("85%");
        org.junit.jupiter.api.Assertions.assertEquals("85", p.value());
        org.junit.jupiter.api.Assertions.assertEquals("%", p.unit());
    }

    @Test
    @DisplayName("纯数字哨兵：1850 不得被误拆出单位 0（R02 明列回归红线）")
    void pureNumberSentinel() {
        TableCell t = TableCell.typeOf("1850");
        org.junit.jupiter.api.Assertions.assertEquals("1850", t.value());
        org.junit.jupiter.api.Assertions.assertNull(t.unit(), "1850 误拆 unit=0 系主迭代已修缺陷，禁回归");
        org.junit.jupiter.api.Assertions.assertEquals("number", t.type());
        TableCell z = TableCell.typeOf("0");
        org.junit.jupiter.api.Assertions.assertEquals("0", z.value());
        org.junit.jupiter.api.Assertions.assertFalse(z.isNull(), "零值不得与缺格混同");
    }

    @Test
    @DisplayName("编码样式：E-01/SP-102 → code；前导零与连字符保真")
    void codeStyle() {
        TableCell e = TableCell.typeOf("E-01");
        org.junit.jupiter.api.Assertions.assertEquals("E-01", e.value());
        org.junit.jupiter.api.Assertions.assertEquals("code", e.type());
        TableCell s = TableCell.typeOf("SP-0102");
        org.junit.jupiter.api.Assertions.assertEquals("SP-0102", s.value(), "前导零不得丢失");
    }

    @Test
    @DisplayName("空值状态区分：空串/空白=缺格 isNull；破折号“—”=string 值（不适用≠缺失）")
    void nullVsDash() {
        org.junit.jupiter.api.Assertions.assertTrue(TableCell.typeOf("").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(TableCell.typeOf("   ").isNull());
        org.junit.jupiter.api.Assertions.assertTrue(TableCell.missing().isNull());
        TableCell dash = TableCell.typeOf("—");
        org.junit.jupiter.api.Assertions.assertFalse(dash.isNull(), "破折号是“不适用”状态，不得并入缺格");
        org.junit.jupiter.api.Assertions.assertEquals("—", dash.value());
        org.junit.jupiter.api.Assertions.assertEquals("string", dash.type());
    }

    @Test
    @DisplayName("复合区间值整体保留 string：-20℃ to 60℃ / 3.7-4.2 kW 不拆散")
    void compoundValuesStayString() {
        TableCell t1 = TableCell.typeOf("-20℃ to 60℃");
        org.junit.jupiter.api.Assertions.assertEquals("string", t1.type());
        org.junit.jupiter.api.Assertions.assertEquals("-20℃ to 60℃", t1.value());
        TableCell t2 = TableCell.typeOf("3.7-4.2 kW");
        org.junit.jupiter.api.Assertions.assertEquals("string", t2.type());
        org.junit.jupiter.api.Assertions.assertEquals("3.7-4.2 kW", t2.value());
    }
}
