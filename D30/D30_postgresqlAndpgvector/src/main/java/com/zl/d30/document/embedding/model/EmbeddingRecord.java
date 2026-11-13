package com.zl.d30.document.embedding.model;

import lombok.Data;

/**
 * 单条 Chunk 的向量化记录：绑定来源 Chunk 与模型元数据，承载真实向量。
 *
 * <p>向量数据类型为 {@code float[]}，与 Spring AI EmbeddingModel 输出一致。</p>
 */
@Data
public class EmbeddingRecord {

    /** 来源文档 ID。 */
    private String documentId;

    /** 来源文档标题。 */
    private String title;

    /** 来源文档版本。 */
    private Integer version;

    /** 来源 Chunk 切分策略。 */
    private String strategy;

    /** 来源 Chunk 索引。 */
    private int chunkIndex;

    /** 生成该向量的模型名称。 */
    private String modelName;

    /** 向量维度。 */
    private int dimensions;

    /** 向量空间标识。 */
    private String vectorSpaceId;

    /** 真实向量（float）。 */
    private float[] vector;

    public EmbeddingRecord() {
    }

    public EmbeddingRecord(String documentId, String title, Integer version, String strategy,
                          int chunkIndex, String modelName, int dimensions,
                          String vectorSpaceId, float[] vector) {
        this.documentId = documentId;
        this.title = title;
        this.version = version;
        this.strategy = strategy;
        this.chunkIndex = chunkIndex;
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.vectorSpaceId = vectorSpaceId;
        this.vector = vector;
    }
}
