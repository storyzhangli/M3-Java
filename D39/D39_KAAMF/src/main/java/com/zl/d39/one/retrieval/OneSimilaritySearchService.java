package com.zl.d39.one.retrieval;

import com.zl.d39.document.embedding.support.VectorValidator;
import com.zl.d39.document.retrieval.dto.SimilarityMatchView;
import com.zl.d39.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d39.document.retrieval.dto.SimilaritySearchResultView;
import com.zl.d39.one.config.OneEmbeddingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * one_ 轨相似度检索应用服务（阿里百炼远程 Embedding，独立实现）。
 *
 * <p>契约对齐 local 轨 {@code SimilaritySearchService}（DTO 只读复用，响应结构逐字段一致）：</p>
 * <ol>
 *   <li>空白拒绝、超长拒绝（UTF-16 字符口径，不静默截断）；</li>
 *   <li>显式 Query Embedding（oneEmbeddingModel，真实远程调用）+ VectorValidator 校验
 *       + 零向量拒绝，保证查询与入库同处 one_ 1024 向量空间；</li>
 *   <li>过滤经 FilterExpressionBuilder 渲染为 SQL/JSON Path 在 one_ 表 WHERE 内真实下推；</li>
 *   <li>similarity=document.getScore()、distance=metadata["distance"]，真实分数不编造；</li>
 *   <li>topK 钳制 1..20 默认 5；threshold=similarity 语义钳制 0..1 默认 0；</li>
 *   <li>vectorSpaceId 取 one_ 配置（bailian|qwen3.7-text-embedding|remote-v1|1024）。</li>
 * </ol>
 *
 * <p>成本口径：每次检索 2 次真实远程调用（显式校验 embed + PgVectorStore 内部 embed），
 * 与 local 轨"显式 embed + store embed"语义一致；正式评测不做查询缓存。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneSimilaritySearchService {

    private final VectorStore oneVectorStore;
    private final EmbeddingModel oneEmbeddingModel;
    private final OneEmbeddingProperties oneProperties;
    private final VectorValidator vectorValidator;

    public OneSimilaritySearchService(@Qualifier("onePgVectorStore") VectorStore oneVectorStore,
                                      @Qualifier("oneEmbeddingModel") EmbeddingModel oneEmbeddingModel,
                                      OneEmbeddingProperties oneProperties,
                                      VectorValidator vectorValidator) {
        this.oneVectorStore = oneVectorStore;
        this.oneEmbeddingModel = oneEmbeddingModel;
        this.oneProperties = oneProperties;
        this.vectorValidator = vectorValidator;
    }

    /** 执行 one_ 轨相似度检索。 */
    public SimilaritySearchResultView search(SimilaritySearchRequest request) {
        long totalStart = System.nanoTime();
        String query = normalizeQuery(request.getQuery());
        int topK = normalizeTopK(request.getTopK());
        double threshold = normalizeThreshold(request.getThreshold());
        boolean filtered = hasFilter(request);

        validateLength(query);

        long embedStart = System.nanoTime();
        float[] queryVector = embedAndValidate(query);
        long embedNanos = System.nanoTime() - embedStart;

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold);
        if (filtered) {
            builder.filterExpression(buildFilterExpression(request));
        }
        long searchStart = System.nanoTime();
        List<Document> documents = oneVectorStore.similaritySearch(builder.build());
        long searchNanos = System.nanoTime() - searchStart;

        List<SimilarityMatchView> matches = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            Double distance = toDouble(metadata == null ? null : metadata.get("distance"));
            Double similarity = toDouble(document.getScore());
            matches.add(SimilarityMatchView.from(
                    document.getId(), document.getText(), metadata, distance, similarity));
        }

        SimilaritySearchResultView view = new SimilaritySearchResultView();
        view.setQuery(query);
        view.setTopK(topK);
        view.setThreshold(threshold);
        view.setVectorSpaceId(oneProperties.vectorSpaceId());
        view.setFiltered(filtered);
        view.setMatches(matches);
        view.setTotal(matches.size());
        view.setQueryEmbeddingMs(toMillis(embedNanos));
        view.setVectorSearchMs(toMillis(searchNanos));
        view.setRetrievalTotalMs(toMillis(System.nanoTime() - totalStart));

        log.info("one_ 相似度检索完成: queryLength={}, topK={}, threshold={}, filtered={}, total={}, dim={}",
                query.length(), topK, threshold, filtered, matches.size(), queryVector.length);
        return view;
    }

    private Filter.Expression buildFilterExpression(SimilaritySearchRequest request) {
        FilterExpressionBuilder expressionBuilder = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> conditions = new ArrayList<>();
        if (isNotBlank(request.getDocumentId())) {
            conditions.add(expressionBuilder.eq("documentId", request.getDocumentId().trim()));
        }
        if (isNotBlank(request.getTitle())) {
            conditions.add(expressionBuilder.eq("title", request.getTitle().trim()));
        }
        if (request.getVersion() != null) {
            conditions.add(expressionBuilder.eq("version", request.getVersion()));
        }
        if (isNotBlank(request.getStrategy())) {
            conditions.add(expressionBuilder.eq("strategy", request.getStrategy().trim()));
        }
        FilterExpressionBuilder.Op combined = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            combined = expressionBuilder.and(combined, conditions.get(i));
        }
        return combined.build();
    }

    private float[] embedAndValidate(String query) {
        float[] vector;
        try {
            vector = oneEmbeddingModel.embed(query);
        } catch (RuntimeException ex) {
            log.error("one_ Query Embedding 调用失败，异常类型: {}", ex.getClass().getSimpleName());
            throw new IllegalArgumentException("查询向量化失败，请稍后重试或检查问题内容");
        }
        vectorValidator.validate(vector, oneProperties.getDimensions());
        if (isZeroVector(vector)) {
            throw new IllegalArgumentException("查询向量为空向量（全零），无法进行相似度检索");
        }
        return vector;
    }

    private void validateLength(String query) {
        int max = oneProperties.getMaxInputLength();
        if (query.length() > max) {
            throw new IllegalArgumentException(
                    "query 长度超过最大输入长度 " + max + "（UTF-16 字符，非 Token），拒绝静默截断");
        }
    }

    private String normalizeQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        return raw.trim();
    }

    private int normalizeTopK(Integer raw) {
        if (raw == null) {
            return SimilaritySearchRequest.DEFAULT_TOP_K;
        }
        if (raw < SimilaritySearchRequest.MIN_TOP_K) {
            return SimilaritySearchRequest.MIN_TOP_K;
        }
        if (raw > SimilaritySearchRequest.MAX_TOP_K) {
            return SimilaritySearchRequest.MAX_TOP_K;
        }
        return raw;
    }

    private double normalizeThreshold(Double raw) {
        if (raw == null) {
            return 0.0d;
        }
        if (raw < 0.0d) {
            return 0.0d;
        }
        if (raw > 1.0d) {
            return 1.0d;
        }
        return raw;
    }

    private boolean hasFilter(SimilaritySearchRequest request) {
        return isNotBlank(request.getDocumentId())
                || request.getVersion() != null
                || isNotBlank(request.getStrategy())
                || isNotBlank(request.getTitle());
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isZeroVector(float[] vector) {
        for (float v : vector) {
            if (v != 0.0f) {
                return false;
            }
        }
        return true;
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private double toMillis(long nanos) {
        return Math.round(nanos / 1_000.0d) / 1_000.0d;
    }
}