package com.zl.d39.document.embedding.config;

import com.zl.d39.document.embedding.config.EmbeddingProperties;
import com.zl.d39.document.embedding.local.LocalHashingEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
     *
     * <p>双轨：标注 {@link Primary} 保证新增 one_ 轨 EmbeddingModel 后，
     * 既有按类型注入（SimilaritySearchService/DocumentEmbeddingService/pgVectorStore）
     * 仍解析到 local，行为零变更（任务书 H1 唯一豁免改动之一）。</p>
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EmbeddingModel.class)
    @ConditionalOnProperty(prefix = "app.ai.embedding", name = "enabled", havingValue = "true", matchIfMissing = true)
    public EmbeddingModel localHashingEmbeddingModel(EmbeddingProperties properties) {
        return new LocalHashingEmbeddingModel(properties);
    }
}
