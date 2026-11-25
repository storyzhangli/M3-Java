package com.zl.d42.rag.rerank;

import com.zl.d42.resilience.ModelExceptionClassifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rerank 装配（P1.5 / D10 / H2）。
 *
 * <p><b>默认关闭</b>：全部 Bean 挂在 {@code app.ai.rag.rerank.enabled=true} 条件之下
 * （无 matchIfMissing——配置缺席即整套不装配，Bean 缺席时编排层 ObjectProvider 取空、
 * 链路逐字节等价，H1）。策略三元枚举 noop|local|remote 恰好装配一个 {@link RerankPort}，
 * 避免多候选歧义；remote 另需二级门禁 {@code remote.enabled=true}。</p>
 *
 * <p>remote.enabled=true 但 strategy 非 remote、或 strategy=remote 但
 * remote.enabled=false 时不装配任何 Port——编排层按缺席 fail-open 原序处理
 * （与"显式注入 RERANK_REMOTE_ENABLED=true 才算授权远程臂"的任务书硬约束一致）。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RerankProperties.class)
public class RerankConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.rag.rerank", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "app.ai.rag.rerank", name = "strategy", havingValue = "noop")
    public RerankPort noopRerankPort() {
        return new NoopRerankAdapter();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.rag.rerank", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "app.ai.rag.rerank", name = "strategy", havingValue = "local")
    public RerankPort localLexicalRerankPort(RerankProperties properties) {
        return new LocalLexicalRerankAdapter(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.ai.rag.rerank", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "app.ai.rag.rerank", name = "strategy", havingValue = "remote")
    @ConditionalOnProperty(prefix = "app.ai.rag.rerank.remote", name = "enabled", havingValue = "true")
    public RerankPort dashScopeRerankPort(RerankProperties properties,
                                          ModelExceptionClassifier modelExceptionClassifier) {
        return new DashScopeRerankAdapter(properties,
                new RerankExceptionClassifier(modelExceptionClassifier));
    }
}
