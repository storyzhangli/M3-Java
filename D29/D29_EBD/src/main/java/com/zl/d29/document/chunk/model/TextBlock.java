package com.zl.d29.document.chunk.model;

import lombok.Data;

/**
 * 清洗后的结构块：标题/段落/列表项的最小语义单元。
 *
 * <p>每个块保留来源页与全局段落索引，并记录其在规范全文中的 UTF-16
 * 偏移区间，供切分窗口映射来源。块通常属于单一物理页（清洗按页进行）。</p>
 */
@Data
public class TextBlock {

    /** 全局段落索引，从 0 开始，跨页连续编号。 */
    private int paragraphIndex;

    /** 来源物理页序号，从 1 开始。 */
    private int pageNumber;

    /** 结构块类型。 */
    private BlockType type;

    /** 清洗后的块文本（不含块间换行）。 */
    private String text;

    /** 块文本 UTF-16 code unit 长度。 */
    private int length;

    /** 在规范全文中的起始偏移（含），UTF-16。 */
    private int globalStart;

    /** 在规范全文中的结束偏移（不含），UTF-16。 */
    private int globalEnd;

    public TextBlock() {
    }

    public TextBlock(int paragraphIndex, int pageNumber, BlockType type, String text) {
        this.paragraphIndex = paragraphIndex;
        this.pageNumber = pageNumber;
        this.type = type;
        this.text = text;
        this.length = text == null ? 0 : text.length();
    }
}
