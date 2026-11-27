package com.zl.d44.one.config;

import com.zl.d44.document.embedding.model.DistanceMetric;
import com.zl.d44.document.embedding.model.EmbeddingModelMetadata;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * one_ 轨远程 Embedding 配置（命名空间 {@code app.one.embedding}）。
 *
 * <p>与 local 轨 {@code app.ai.embedding} 完全独立的命名空间：双轨并存，
 * 禁止通过切换 local 轨 provider 实现"接入"（那会替换 local 模型，违反双轨契约）。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>默认 {@code enabled=false}：关闭时本配置 validate 短路，应用与骨架行为一致、零远程调用；</li>
 *   <li>密钥/地址只经环境变量注入（DASHSCOPE_API_KEY / BAILIAN_EMBEDDING_BASE_URL），禁止硬编码；</li>
 *   <li>dimensions 默认 0 占位，必须由环境变量 ONE_EMBEDDING_DIMENSIONS 注入 P0 探测冻结值（1024）；
 *       上限 2000 为 pgvector HNSW 对 vector 类型的硬红线，结构性防止非法建表；</li>
 *   <li>modelVersion 为配置级版本指纹标签（服务端未暴露独立版本号），服务端模型变更须人工 bump。</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "app.one.embedding")
public class OneEmbeddingProperties {

    /** 是否启用 one_ 轨：默认关闭，开启须显式注入 ONE_EMBEDDING_ENABLED=true。 */
    private boolean enabled = false;

    /** 模型提供方标识（进入 vectorSpaceId）。 */
    private String provider = "bailian";

    /** 模型名称：用户点名型号，P0 探测已核实有效（G1 PASS，服务端回显同名）。 */
    private String model = "qwen3.7-text-embedding";

    /** 配置级版本指纹标签：服务端模型变更时须人工 bump 并重建 one_ 空间。 */
    private String modelVersion = "remote-v1";

    /** OpenAI 兼容服务基址（环境变量 BAILIAN_EMBEDDING_BASE_URL 注入）。 */
    private String baseUrl = "";

    /** API 密钥（环境变量 DASHSCOPE_API_KEY 注入，禁止硬编码/落日志）。 */
    private String apiKey = "";

    /** 向量维度：P0 冻结值 1024，经 ONE_EMBEDDING_DIMENSIONS 注入；0 为未配置占位。 */
    private int dimensions = 0;

    /** 单次 HTTP 请求最大文本条数：P0 G3 实测上限 16（25 返回 400）。 */
    private int maxBatch = 16;

    /** 最大输入长度（UTF-16 字符，非 Token），超长拒绝不静默截断。 */
    private int maxInputLength = 8192;

    /** 距离度量：固定 COSINE（远端输出未归一化，余弦对模长不敏感，口径与 local 轨一致）。 */
    private DistanceMetric distanceMetric = DistanceMetric.COSINE;

    /** 建连（含 TLS 握手）超时。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 读取超时（两次数据之间的等待上限）。 */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** 一次逻辑调用（含全部重试）总墙钟上限。 */
    private Duration totalDeadline = Duration.ofSeconds(60);

    /** 总尝试次数（含首次），默认 2 = 最多重试 1 次，取值 1..5。 */
    private int maxAttempts = 2;

    /** 读取超时是否允许重试：默认关闭（请求已发送、结果不确定），对齐 app.ai.resilience 口径。 */
    private boolean retryOnReadTimeout = false;

    /** 首次退避毫秒。 */
    private long initialBackoffMs = 200;

    /** 退避倍率。 */
    private double backoffMultiplier = 2.0;

    /** 单次退避上限毫秒。 */
    private long maxBackoffMs = 5000;

    /** 抖动因子（0~1）。 */
    private double jitterFactor = 0.2;

    /** 是否携带 dimensions 请求参数：P0 证实模型原生输出 1024，无需降维，默认不携带。 */
    private boolean sendDimensionsParam = false;

    /**
     * 启动校验：enabled=false 时短路（@ConfigurationPropertiesScan 会无条件注册本类）；
     * enabled=true 时关键项缺一即 fail-fast，禁止带病启动。
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("app.one.embedding.provider 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("app.one.embedding.model 不能为空");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("app.one.embedding.modelVersion 不能为空");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("one_ 轨启用时必须经环境变量 BAILIAN_EMBEDDING_BASE_URL 注入 baseUrl");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("one_ 轨启用时必须经环境变量 DASHSCOPE_API_KEY 注入 apiKey");
        }
        if (dimensions < 1 || dimensions > 2000) {
            throw new IllegalArgumentException("one_ 轨 dimensions 必须经 ONE_EMBEDDING_DIMENSIONS 注入 P0 冻结值（1..2000，HNSW vector 红线），当前=" + dimensions);
        }
        if (maxBatch < 1) {
            throw new IllegalArgumentException("app.one.embedding.maxBatch 必须为正");
        }
        if (maxInputLength < 1) {
            throw new IllegalArgumentException("app.one.embedding.maxInputLength 必须为正");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("app.one.embedding.maxAttempts 必须在 1..5 之间");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || totalDeadline == null || totalDeadline.isNegative() || totalDeadline.isZero()) {
            throw new IllegalArgumentException("one_ 轨超时配置必须为正");
        }
        if (initialBackoffMs < 0 || maxBackoffMs < initialBackoffMs || backoffMultiplier < 1.0
                || jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("one_ 轨退避配置非法");
        }
        if (distanceMetric != DistanceMetric.COSINE) {
            throw new IllegalArgumentException("one_ 轨距离度量固定 COSINE（与冻结参数/正式表口径一致）");
        }
    }

    /** one_ 轨向量空间标识：bailian|qwen3.7-text-embedding|remote-v1|1024。 */
    public String vectorSpaceId() {
        return EmbeddingModelMetadata.buildVectorSpaceId(provider, model, modelVersion, dimensions);
    }
}