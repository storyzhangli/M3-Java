package com.zl.d44.hybrid.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 混合检索请求：字段与校验口径完全对齐 {@code SimilaritySearchRequest}（controlled 对比前提），
 * 服务层再做权威归一（topK 1..20 默认 5；threshold 0..1 默认 0，similarity 语义）。
 */
@Data
public class HybridSearchRequest {

    @NotBlank(message = "query 不能为空")
    private String query;

    @Min(value = 1, message = "topK 不能小于 1")
    @Max(value = 20, message = "topK 不能大于 20")
    private Integer topK;

    /** 相似度阈值（仅作用于向量路；RRF 分不参与阈值过滤——量纲红线 §3.2）。 */
    @Min(value = 0, message = "threshold 不能小于 0")
    @Max(value = 1, message = "threshold 不能大于 1")
    private Double threshold;

    private String documentId;

    private Integer version;

    private String strategy;

    private String title;
}
