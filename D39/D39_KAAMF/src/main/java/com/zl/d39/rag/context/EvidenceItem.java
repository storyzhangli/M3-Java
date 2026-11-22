package com.zl.d39.rag.context;

import com.zl.d39.document.model.DocumentLifecycleStatus;
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

    /** 起始物理页（从 1 开始）；无定位时为 null，不推断。 */
    private Integer page;

    /** 跨页结束物理页；单页或无定位时为 null。 */
    private Integer lastPage;

    /** 起始段落序号（从 0 开始）；无定位时为 null。 */
    private Integer paragraphIndex;

    /** 引用片段：由真实内容确定性截取，模型不参与，缺失为 null。 */
    private String quotedSnippet;

    /** 是否被模型回答实际引用，默认 false，由响应前核验回填。 */
    private boolean cited;

    /** 该证据渲染块的字符长度。 */
    private int blockLength;
}
