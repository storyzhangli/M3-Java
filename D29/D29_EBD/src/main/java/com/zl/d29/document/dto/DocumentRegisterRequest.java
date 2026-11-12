package com.zl.d29.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文档登记请求（仅元数据，不含文件）。
 */
@Data
public class DocumentRegisterRequest {

    /** 文档来源（对应 D25 sourceSystem）。 */
    @NotBlank(message = "文档来源不能为空")
    @Size(max = 64, message = "文档来源长度不能超过 64")
    private String source;

    /** 文档标题。 */
    @NotBlank(message = "文档标题不能为空")
    @Size(max = 200, message = "文档标题长度不能超过 200")
    private String title;

    /** 可选：外部指定文档标识；为空时服务端生成。 */
    @Size(max = 64, message = "文档标识长度不能超过 64")
    private String documentId;
}
