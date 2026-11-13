package com.zl.d30.document.chunk.dto;

import lombok.Data;

import java.util.List;

/**
 * Chunk 对外视图：含来源元数据、长度口径与定位能力标志。
 */
@Data
public class ChunkView {

    private String documentId;
    private String title;
    private Integer version;
    private String strategy;
    private int chunkIndex;
    private String content;

    /** UTF-16 code unit 长度（非 Token 数）。 */
    private int length;

    /** Unicode 码点数。 */
    private int codePointCount;

    private List<ChunkSourceView> sources;
    private List<Integer> pageNumbers;
    private Integer firstPageNumber;
    private Integer lastPageNumber;
    private List<Integer> paragraphIndices;
    private boolean crossPage;
    private boolean hasPageLocation;
    private boolean hasParagraphLocation;
}
