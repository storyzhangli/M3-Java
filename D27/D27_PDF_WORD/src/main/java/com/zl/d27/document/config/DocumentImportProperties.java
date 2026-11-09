package com.zl.d27.document.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档导入配置（前缀 {@code app.ai.document}）。
 *
 * <p>所有限制均在服务端生效，可通过环境变量覆盖；不依赖前端校验。
 * 仅做绑定与默认值，不包含供应商专有类型。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.document")
public class DocumentImportProperties {

    /** 业务层文件大小上限（字节），默认 2MB；与容器 multipart 限制分层、独立生效。 */
    private long maxFileSizeBytes = 2L * 1024 * 1024;

    /** 允许的扩展名（小写、不含点），默认 txt/md/pdf。 */
    private List<String> allowedExtensions = new ArrayList<>(List.of("txt", "md", "pdf"));

    /** 允许的内容类型；结合扩展名共同判断，不仅看扩展名。 */
    private List<String> allowedContentTypes = new ArrayList<>(
            List.of("text/plain", "text/markdown", "application/pdf"));

    /** 文本解码字符集，默认 UTF-8。 */
    private String charset = "UTF-8";

    /** 标题最大长度，超出安全截断。 */
    private int titleMaxLength = 200;
}
