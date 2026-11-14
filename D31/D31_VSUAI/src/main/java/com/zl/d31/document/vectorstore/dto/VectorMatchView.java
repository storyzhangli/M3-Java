package com.zl.d31.document.vectorstore.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

/**
 * 相似度匹配结果视图。
 */
@Data
@AllArgsConstructor
public class VectorMatchView {

    /** 文档 ID（PgVectorStore 主键对应）。 */
    private String id;

    /** 原始文本。 */
    private String text;

    /** 相似度得分（余弦，越接近 1 越相似；由 1 - 余弦距离换算）。 */
    private double score;

    /** 元数据。 */
    private Map<String, Object> metadata;
}
