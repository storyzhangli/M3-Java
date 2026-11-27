package com.zl.d44.rag.rerank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rerank 配置（前缀 {@code app.ai.rag.rerank}，任务书 H2/P1.5 冻结命名）。
 *
 * <p><b>默认关闭</b>：{@code enabled} 缺省 false（条件装配无 matchIfMissing），
 * 缺席时全套 rerank Bean 不装配、链路零差异（H1/H2）。远程二级门禁
 * {@code remote.enabled} 缺省 false，须显式 {@code RERANK_REMOTE_ENABLED=true}
 * 且注入 {@code DASHSCOPE_API_KEY} 才可能装配（缺省零远程，H9）。</p>
 *
 * <p>注意：既有 {@code com.zl.d44.rag.config.RagProperties} 实际前缀为 {@code app.rag}，
 * 与本类前缀不同、互不影响（偏差 D-EX4 记录）。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.ai.rag.rerank")
public class RerankProperties {

    /** 精排总开关；false=全套不装配（默认，H2）。 */
    private boolean enabled = false;

    /** 策略枚举：noop | local | remote（配置选择唯一适配器，无运行时混选）。 */
    private String strategy = "noop";

    /**
     * 输出上限（A3 截断臂开关）；0=不截断（保留全部候选，纯重排不改集合，D2/§3.1）。
     * 生效值 = min(topN, maxOutput>0 ? maxOutput : topN)。
     */
    private int maxOutput = 0;

    /** 远程臂配置（仅 strategy=remote 且 remote.enabled=true 时使用）。 */
    private Remote remote = new Remote();

    /**
     * 远程精排参数（gte-rerank-v2）。全部数值口径经 KP4 冻结于 rerank-protocol.json，
     * 禁看结果后调整（H5/熔断#5）。
     */
    @Data
    public static class Remote {

        /** 远程二级门禁（默认 false；H2）。 */
        private boolean enabled = false;

        /** API Key，仅环境变量注入（H9），默认空=装配后调用即 fail-open。 */
        private String apiKey = "";

        /** 模型名（javap 实证常量 gte-rerank-v2）。 */
        private String model = "gte-rerank-v2";

        /**
         * D-EX8：原生 HTTP 端点（SDK TextReRank 构造路径缺陷，A2 通道改直连）。
         * 口径与百炼官方 text-rerank REST 端点一致，可经 env 覆盖。
         */
        private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

        /** 建连超时（毫秒）。 */
        private int connectTimeoutMs = 3000;

        /** 读取超时（毫秒）。 */
        private int readTimeoutMs = 5000;

        /** 单文档 token 上限（官方 4000；超限输入侧截断标 TRUNCATED，E4）。 */
        private int perDocTokenLimit = 4000;

        /** 请求总 token 上限（官方 30000；预检超限拒绝回退标 OVERSIZED，E4）。 */
        private int requestTokenLimit = 30000;

        /** token 估算除数：tokens=ceil(utf16Chars/1.3)，中文向保守近似，口径冻结（estimated 标注）。 */
        private double tokenCharDivisor = 1.3d;

        /** 预算三重帽：累计 call 上限（熔断#6）。 */
        private int budgetMaxCalls = 400;

        /** 预算三重帽：累计实测 token 上限。 */
        private long budgetMaxTokens = 2_000_000L;

        /** 预算三重帽：累计费用上限（元）。 */
        private double budgetMaxCny = 5.0d;

        /** 官方单价（元/百万 token，已查证 0.8；费用=实测 totalTokens x 本值 / 1e6，H4）。 */
        private double priceCnyPerMillionTokens = 0.8d;
    }
}
