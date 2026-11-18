package com.zl.d35.rag.dto;

import lombok.Data;

/**
 * 证据来源视图。
 *
 * <p><b>语义边界</b>：这里表示“本次实际检索且进入上下文、提供给模型的证据来源”，
 * 不声称每条都被模型实际逐条引用。</p>
 */
@Data
public class RagSourceView {

    /** 上下文内唯一来源标识，如 S1、S2。 */
    private String sourceRef;

    /** 稳定 Chunk 标识。 */
    private String chunkId;

    /** 来源文档标识。 */
    private String documentId;

    /** 文档标题。 */
    private String title;

    /** 版本。 */
    private Integer version;

    /** Chunk 在文档内序号。 */
    private Integer chunkIndex;

    /** 切分策略。 */
    private String strategy;

    /** 真实相似度（可能为 null，不编造）。 */
    private Double similarity;

    /** 真实余弦距离（可能为 null）。 */
    private Double distance;
}
