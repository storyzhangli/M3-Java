package com.zl.d39.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 证据来源视图。
 *
 * <p><b>语义边界</b>：这里表示“本次实际检索且进入上下文、提供给模型的证据来源”，
 * 其中 {@code cited} 标识该来源是否被模型回答实际引用。</p>
 *
 * <p>类级 {@link JsonInclude}(NON_NULL)：page/paragraphIndex/quotedSnippet/url 等
 * 缺失字段直接隐藏，不输出空串或占位，不补造页码与 URL。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RagSourceView {

    /** 稳定来源标识：跨请求稳定，优先映射真实 documentId。 */
    private String sourceId;

    /** 请求级引用标识：仅本轮有效，对应 sourceRef（S1/S2…）。 */
    private String citationId;

    /** 上下文内唯一来源标识，如 S1、S2（向后兼容字段）。 */
    private String sourceRef;

    /** 稳定 Chunk 标识（UUID v5）。 */
    private String chunkId;

    /** 来源文档稳定标识（向后兼容，与 sourceId 同源）。 */
    private String documentId;

    /** 文档标题。 */
    private String title;

    /** 版本。 */
    private Integer version;

    /** Chunk 在文档内序号。 */
    private Integer chunkIndex;

    /** 起始物理页（从 1 开始）；无定位时隐藏，不推断。 */
    private Integer page;

    /** 跨页结束物理页；单页或无定位时隐藏。 */
    private Integer lastPage;

    /** 起始段落序号（从 0 开始）；无定位时隐藏。 */
    private Integer paragraphIndex;

    /** 引用片段：真实内容有限截取，模型不参与；缺失时隐藏。 */
    private String quotedSnippet;

    /** 是否被模型回答实际引用。 */
    private boolean cited;

    /** 切分策略。 */
    private String strategy;

    /** 真实相似度（可能为 null，不编造）。 */
    private Double similarity;

    /** 真实余弦距离（可能为 null）。 */
    private Double distance;

    /**
     * 来源 URL：仅当真实元数据提供且通过安全策略时返回；当前无授权外链来源，恒为 null。
     */
    private String url;
}
