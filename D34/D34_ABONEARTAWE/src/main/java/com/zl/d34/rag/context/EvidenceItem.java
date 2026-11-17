package com.zl.d34.rag.context;

import com.zl.d34.document.model.DocumentLifecycleStatus;
import lombok.Data;

/**
 * 进入上下文的证据项：内容 + 唯一来源标识 + 真实 Chunk 元数据。
 */
@Data
public class EvidenceItem {

    /** 上下文内唯一来源标识，如 S1。 */
    private String sourceRef;

    /** 稳定 Chunk 标识。 */
    private String chunkId;

    /** Chunk 文本。 */
    private String content;

    /** 来源文档标识。 */
    private String documentId;

    /** 文档标题。 */
    private String title;

    /** 版本。 */
    private Integer version;

    /** Chunk 序号。 */
    private Integer chunkIndex;

    /** 切分策略。 */
    private String strategy;

    /** 真实相似度（可能为 null）。 */
    private Double similarity;

    /** 真实距离（可能为 null）。 */
    private Double distance;

    /** 生命周期状态（评估阶段解析后回填，默认 ACTIVE）。 */
    private DocumentLifecycleStatus lifecycleStatus = DocumentLifecycleStatus.ACTIVE;

    /** 该证据渲染块的字符长度。 */
    private int blockLength;
}
