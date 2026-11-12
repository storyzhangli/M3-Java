package com.zl.d29.document.embedding.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 兼容性检查请求：描述候选向量空间。
 */
@Data
public class CompatibilityCheckRequest {

    @NotBlank(message = "provider 不能为空")
    private String provider;

    @NotBlank(message = "modelName 不能为空")
    private String modelName;

    @NotBlank(message = "modelVersion 不能为空")
    private String modelVersion;

    @Min(value = 1, message = "dimensions 必须为正")
    private int dimensions;

    private int indexVersion;

    private String distanceMetric;
}
