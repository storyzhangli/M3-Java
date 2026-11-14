package com.zl.d31.document.chunk.dto;

import lombok.Data;

/**
 * Chunk 来源定位视图。
 */
@Data
public class ChunkSourceView {

    /** 来源物理页序号；无可靠页码时为 {@code null}。 */
    private Integer pageNumber;

    /** 来源段落索引；无可靠段落信息时为 {@code null}。 */
    private Integer paragraphIndex;

    /** 全局起始偏移（UTF-16）。 */
    private int globalStart;

    /** 全局结束偏移（UTF-16）。 */
    private int globalEnd;
}
