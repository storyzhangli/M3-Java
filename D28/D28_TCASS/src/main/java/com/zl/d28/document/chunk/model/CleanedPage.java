package com.zl.d28.document.chunk.model;

import lombok.Data;

/**
 * 单页清洗摘要：记录清洗前后长度与空白判定，保留页面来源。
 */
@Data
public class CleanedPage {

    /** 物理页序号，从 1 开始。 */
    private int pageNumber;

    /** 清洗前该页文本 UTF-16 长度。 */
    private int originalLength;

    /** 清洗后该页文本 UTF-16 长度。 */
    private int cleanedLength;

    /** 清洗后是否仍为空白页（无有效字符）。 */
    private boolean blank;

    public CleanedPage() {
    }

    public CleanedPage(int pageNumber, int originalLength, int cleanedLength, boolean blank) {
        this.pageNumber = pageNumber;
        this.originalLength = originalLength;
        this.cleanedLength = cleanedLength;
        this.blank = blank;
    }
}
