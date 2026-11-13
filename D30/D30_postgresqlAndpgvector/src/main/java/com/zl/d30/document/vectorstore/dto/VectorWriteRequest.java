package com.zl.d30.document.vectorstore.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 向量写入请求：一组文本项及其可选元数据。
 */
@Data
public class VectorWriteRequest {

    /** 待写入文本项。 */
    @NotEmpty(message = "写入项不能为空")
    @Valid
    private List<Item> items;

    /**
     * 单个写入项。
     */
    @Data
    public static class Item {

        /** 文本内容（空白在服务层拒绝）。 */
        private String text;

        /** 可选元数据，需为 JSON 可序列化的简单类型。 */
        private Map<String, Object> metadata;
    }
}
