package com.zl.d39.one.config;

import com.zl.d39.one.client.OneBailianEmbeddingClient;
import com.zl.d39.one.config.OneEmbeddingProperties;
import com.zl.d39.one.embedding.OneRemoteEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * one_ 轨 Embedding 装配：独立条件开关 {@code app.one.embedding.enabled}（默认 false）。
 *
 * <p>双 Bean 共存设计（任务书 §3.1）：</p>
 * <ul>
 *   <li>local 轨 {@code localHashingEmbeddingModel} 带 {@code @ConditionalOnMissingBean(EmbeddingModel.class)}，
 *       组件扫描顺序 document &lt; one 保证 local 先注册；本类 Bean 后注册，local 加 {@code @Primary}
 *       后既有按类型注入仍解析到 local；</li>
 *   <li><b>装配顺序守卫</b>：{@code oneEmbeddingModel} 显式依赖
 *       {@code @Qualifier("localHashingEmbeddingModel")}——若扫描顺序异常导致 local Bean 被条件回退，
 *       启动立即抛 NoSuchBeanDefinitionException（响亮失败），而非静默把 local 链路换成远程模型；</li>
 *   <li>关闭时本配置类整体不装配，应用与骨架完全一致、零远程调用。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneEmbeddingConfiguration {

    @Bean
    public OneBailianEmbeddingClient oneBailianEmbeddingClient(OneEmbeddingProperties properties) {
        return new OneBailianEmbeddingClient(properties);
    }

    /**
     * one_ 轨 EmbeddingModel：非 Primary，注入必须显式 Qualifier。
     *
     * @param localGuard 装配顺序守卫（见类注释）：强制 local Bean 存在，防止条件回退静默换轨。
     */
    @Bean
    public EmbeddingModel oneEmbeddingModel(OneEmbeddingProperties properties,
                                            OneBailianEmbeddingClient oneBailianEmbeddingClient,
                                            @Qualifier("localHashingEmbeddingModel") EmbeddingModel localGuard) {
        return new OneRemoteEmbeddingModel(properties, oneBailianEmbeddingClient);
    }
}