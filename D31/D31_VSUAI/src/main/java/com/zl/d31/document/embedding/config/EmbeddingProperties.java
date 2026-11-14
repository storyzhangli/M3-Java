package com.zl.d31.document.embedding.config;

import com.zl.d31.document.embedding.model.DistanceMetric;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding 配置（命名空间 {@code app.ai.embedding}）。
 *
 * <p>配置与算法分离，支持环境变量覆盖；密钥通过环境变量注入，禁止硬编码。
 * 非法配置在 {@link #validate()} 阶段 fail-fast。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.embedding")
public class EmbeddingProperties {

    /** 模型提供方：默认本地。 */
    private String provider = "local";

    /** 模型名称。 */
    private String model = "local-hashing-embedding";

    /** 模型版本/部署标识。 */
    private String modelVersion = "local-v1";

    /** 服务地址（本地模型为空）。 */
    private String baseUrl = "";

    /** API 密钥（本地模型为空，云端通过环境变量注入）。 */
    private String apiKey = "";

    /** 向量维度：环境基线统一为 384，必须与真实模型输出、vectorSpaceId、正式表 vector(N) 一致。 */
    private int dimensions = 384;

    /** 最大输入长度（UTF-16 字符，非 Token）。 */
    private int maxInputLength = 8192;

    /** 是否启用。 */
    private boolean enabled = true;

    /** 距离度量方式。 */
    private DistanceMetric distanceMetric = DistanceMetric.COSINE;

    /** 最大尝试次数（含首次），取值 1..5。 */
    private int maxAttempts = 3;

    /** 首次退避毫秒。 */
    private long initialBackoffMs = 200;

    /** 退避倍率。 */
    private double backoffMultiplier = 2.0;

    /** 单次退避上限毫秒。 */
    private long maxBackoffMs = 5000;

    /**
     * 启动校验：保证配置自洽，避免运行期才暴露问题。
     */
    @PostConstruct
    public void validate() {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("embedding.provider 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("embedding.model 不能为空");
        }
        if (dimensions < 1 || dimensions > 100000) {
            throw new IllegalArgumentException("embedding.dimensions 必须在 1..100000 之间");
        }
        if (maxInputLength < 1) {
            throw new IllegalArgumentException("embedding.maxInputLength 必须为正");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("embedding.maxAttempts 必须在 1..5 之间");
        }
        if (initialBackoffMs < 0 || maxBackoffMs < initialBackoffMs) {
            throw new IllegalArgumentException("embedding 退避配置非法");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("embedding.backoffMultiplier 不能小于 1");
        }
        if (distanceMetric == null) {
            throw new IllegalArgumentException("embedding.distanceMetric 不能为空");
        }
        // 云端模型必须提供地址与密钥；本地模型不要求。
        if (!"local".equalsIgnoreCase(provider)) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("云端 Embedding 必须配置 baseUrl");
            }
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("云端 Embedding 必须通过环境变量注入 apiKey");
            }
        }
    }

    /** 当前配置对应的向量空间标识。 */
    public String vectorSpaceId() {
        return com.zl.d31.document.embedding.model.EmbeddingModelMetadata
                .buildVectorSpaceId(provider, model, modelVersion, dimensions);
    }
}
