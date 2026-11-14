package com.zl.d31.document.embedding.config;

import com.zl.d31.document.embedding.local.LocalHashingEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 装配配置：依据应用自有命名空间 {@code app.ai.embedding} 构造 EmbeddingModel。
 *
 * <p>当前环境装配本地哈希词面模型；该抽象与具体模型解耦，后续接入云端模型时
 * 只需在此扩展并通过配置切换，不影响服务/校验/兼容性上层。</p>
 */
@Configuration
public class EmbeddingConfiguration {

    /**
     * 本地哈希词面模型：默认启用，零网络零密钥。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(prefix = "app.ai.embedding", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingModel localHashingEmbeddingModel(EmbeddingProperties properties) {
        return new LocalHashingEmbeddingModel(properties);
    }
}
