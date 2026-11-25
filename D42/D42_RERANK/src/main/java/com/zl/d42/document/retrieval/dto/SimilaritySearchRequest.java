package com.zl.d42.document.retrieval.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 相似度检索请求（最小可用接口入参）。
 *
 * <p>字段口径：</p>
 * <ul>
 *   <li>{@code query}：用户原始问题，空白拒绝；超长（超过 Embedding 配置的最大输入长度）
 *       在服务层显式拒绝，<b>不做静默截断</b>；</li>
 *   <li>{@code topK}：返回条数，<b>默认 5</b>；开放范围 1..20，边界防止一次检索拉取过多资源；</li>
 *   <li>{@code threshold}：相似度阈值（similarity，非 distance），范围 0..1，默认 0 表示不额外过滤；</li>
 *   <li>过滤字段仅使用入库时真实写入、且有业务意义的 metadata 键：
 *       documentId / version / strategy / title，均可选；不提供则不做过滤。</li>
 * </ul>
 */
@Data
public class SimilaritySearchRequest {

    /** 返回条数上限（含边界），任务要求默认 5。 */
    public static final int DEFAULT_TOP_K = 5;

    /** 开放给调用方的最大 Top-K，防止资源消耗。 */
    public static final int MAX_TOP_K = 20;

    /** 开放给调用方的最小 Top-K。 */
    public static final int MIN_TOP_K = 1;

    /** 用户问题（自然语言）。 */
    @NotBlank(message = "query 不能为空")
    private String query;

    /**
     * 返回条数，默认 5；范围 1..20。
     * 为 null 时由服务层归一为 {@link #DEFAULT_TOP_K}。
     */
    @Min(value = 1, message = "topK 不能小于 1")
    @Max(value = 20, message = "topK 不能大于 20")
    private Integer topK;

    /**
     * 相似度阈值（similarity 语义），范围 0..1，默认 0。
     * 框架内部会换算为距离边界（1 - similarity），本字段不接受 distance 口径。
     */
    @Min(value = 0, message = "threshold 不能小于 0")
    @Max(value = 1, message = "threshold 不能大于 1")
    private Double threshold;

    /** 来源文档标识过滤（metadata.documentId），可选。 */
    private String documentId;

    /** 版本过滤（metadata.version），可选。 */
    private Integer version;

    /** 切分策略过滤（metadata.strategy，如 FIXED/STRUCTURE），可选。 */
    private String strategy;

    /** 文档标题过滤（metadata.title），可选。 */
    private String title;
}
