package com.zl.d29.document.chunk.model;

import lombok.Data;

/**
 * Chunk 的单个来源定位：记录来源页、段落与全局偏移区间。
 *
 * <p>一个 Chunk 可含多个来源（跨页/跨段合并），因此用列表承载，
 * 不允许只保留第一个来源而丢弃其他来源。</p>
 *
 * <p>当原始文本没有可靠页码或段落信息时，对应字段允许为空，
 * 并由 Chunk 的定位能力标志标注边界。</p>
 */
@Data
public class ChunkSource {

    /** 来源物理页序号，从 1 开始；无可靠页码时为 {@code null}。 */
    private Integer pageNumber;

    /** 来源全局段落索引，从 0 开始；无可靠段落信息时为 {@code null}。 */
    private Integer paragraphIndex;

    /** 该来源在规范全文中的起始偏移（含），UTF-16。 */
    private int globalStart;

    /** 该来源在规范全文中的结束偏移（不含），UTF-16。 */
    private int globalEnd;

    public ChunkSource() {
    }

    public ChunkSource(Integer pageNumber, Integer paragraphIndex, int globalStart, int globalEnd) {
        this.pageNumber = pageNumber;
        this.paragraphIndex = paragraphIndex;
        this.globalStart = globalStart;
        this.globalEnd = globalEnd;
    }
}
