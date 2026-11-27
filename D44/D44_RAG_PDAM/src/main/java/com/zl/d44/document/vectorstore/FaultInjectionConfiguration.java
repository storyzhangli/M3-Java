package com.zl.d44.document.vectorstore;

import com.zl.d44.document.vectorstore.FaultInjectingVectorStore;
import com.zl.d44.document.vectorstore.FaultInjectionProperties;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * P5（H2/H5，KP3 冻结）：受控故障注入装配。
 *
 * <p>门禁：{@code app.vector-index.fault-injection.enabled=true} 显式开启——
 * <b>无 matchIfMissing，默认 false，主 profile 永不装配</b>（C18/熔断#6）。</p>
 *
 * <p>装配语义（"@Primary 包装真实 VectorStore Bean"的落地形）：</p>
 * <ol>
 *   <li>装饰器 Bean 标注 {@link Primary}，接管既有按类型注入点
 *       （DocumentVectorIndexService / PgVectorStoreService / SimilaritySearchService）；</li>
 *   <li>static {@link BeanFactoryPostProcessor} 将 {@code pgVectorStore} 的
 *       BeanDefinition primary 置 false——避免同类型双 @Primary 歧义
 *       （Spring 对多 primary 抛 NoUniqueBeanDefinitionException，实测语义）；</li>
 *   <li>装饰器内部按 bean 名 {@code getObject("pgVectorStore")} 延迟取真实实例，
 *       不受 primary 翻转影响，无自引用。</li>
 * </ol>
 *
 * <p>one_ 轨（D38）注入点全部显式 {@code @Qualifier("onePgVectorStore")}，
 * 与 primary 翻转无关——本装配不波及百炼隔离轨（H6 域隔离）。
 * BFPP 为 static Bean 方法，先于一切单例实例化执行；pgVectorStore 定义缺席
 * （app.pgvector.enabled=false）时保护性跳过。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.vector-index.fault-injection", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FaultInjectionProperties.class)
public class FaultInjectionConfiguration {

    @Bean
    static BeanFactoryPostProcessor demotePgVectorStorePrimaryPostProcessor() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition("pgVectorStore")
                    && beanFactory.getBeanDefinition("pgVectorStore") instanceof AbstractBeanDefinition bd) {
                bd.setPrimary(false);
            }
        };
    }

    @Bean
    @Primary
    public FaultInjectingVectorStore faultInjectingVectorStore(
            @org.springframework.beans.factory.annotation.Qualifier("pgVectorStore") VectorStore delegate,
            FaultInjectionProperties properties) {
        return new FaultInjectingVectorStore(delegate, properties);
    }
}
