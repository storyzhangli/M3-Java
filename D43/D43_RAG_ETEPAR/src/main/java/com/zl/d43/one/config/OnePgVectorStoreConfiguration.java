package com.zl.d43.one.config;

import com.zl.d43.one.config.OneEmbeddingProperties;
import com.zl.d43.one.config.OnePgVectorProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * one_ 轨 PgVectorStore 装配：独立表 {@code one_d43_vector_store}（vector(1024)/HNSW/COSINE）。
 *
 * <p>复用 local 轨 {@code pgVectorJdbcTemplate}（同一 PG 库 spring_ai，不同表）；
 * 依赖 {@code app.pgvector.enabled=true}（local 轨 PG 基础设施），关闭时本 Bean 无法装配（响亮失败）。</p>
 *
 * <p>维度断言对齐 local 轨风格：one_ 模型冻结维度必须等于 one_ 配置维度，否则拒绝建表。
 * 「先 validate 后 initialize」顺序坑沿用 Builder 默认 false + catalog 独立核验（D37 教训）。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OnePgVectorStoreConfiguration {

    @Bean
    public PgVectorStore onePgVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                          @Qualifier("oneEmbeddingModel") EmbeddingModel oneEmbeddingModel,
                                          OneEmbeddingProperties oneEmbeddingProperties,
                                          OnePgVectorProperties onePgVectorProperties) {
        int dimensions = oneEmbeddingProperties.getDimensions();
        int modelDimensions = oneEmbeddingModel.dimensions();
        if (modelDimensions != dimensions) {
            throw new IllegalStateException(
                    "one_ EmbeddingModel 维度与配置维度不一致：model=" + modelDimensions
                            + "，config=" + dimensions + "，禁止创建向量表");
        }
        return PgVectorStore.builder(pgVectorJdbcTemplate, oneEmbeddingModel)
                .schemaName(onePgVectorProperties.getSchema())
                .vectorTableName(onePgVectorProperties.getTable())
                .dimensions(dimensions)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(onePgVectorProperties.isInitializeSchema())
                .maxDocumentBatchSize(onePgVectorProperties.getMaxDocumentBatchSize())
                .build();
    }
}