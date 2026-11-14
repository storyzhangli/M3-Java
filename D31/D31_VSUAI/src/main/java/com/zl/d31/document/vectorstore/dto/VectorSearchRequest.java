package com.zl.d31.document.vectorstore.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 相似度检索请求。
 */
@Data
public class VectorSearchRequest {

    /** 检索文本（真实经 EmbeddingModel 向量化，非字符匹配）。 */
    @NotBlank(message = "检索文本不能为空")
    private String query;

    /** 返回条数，1..20。 */
    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 20, message = "topK 最大为 20")
    private int topK = 3;

    /** 相似度阈值（0..1），低于该值的结果被过滤；0 表示不过滤。 */
    @Min(value = 0, message = "threshold 不能小于 0")
    @Max(value = 1, message = "threshold 不能大于 1")
    private double threshold = 0.0;
}
