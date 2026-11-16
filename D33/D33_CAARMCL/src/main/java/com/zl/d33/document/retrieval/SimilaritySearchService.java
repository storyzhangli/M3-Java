package com.zl.d33.document.retrieval;

import com.zl.d33.document.embedding.config.EmbeddingProperties;
import com.zl.d33.document.embedding.support.VectorValidator;
import com.zl.d33.document.retrieval.dto.SimilarityMatchView;
import com.zl.d33.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d33.document.retrieval.dto.SimilaritySearchResultView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 相似度检索应用服务：打通“用户问题 → Query Embedding → VectorStore 相似度检索 → Top-K 结果”。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>查询校验：空白拒绝、超长拒绝（不静默截断）；</li>
 *   <li>Query Embedding：用与入库<b>同一</b>的 {@link EmbeddingModel} 显式向量化并校验，
 *       处理调用失败、空向量、维度不符，保证查询与入库处于同一 384 向量空间；</li>
 *   <li>统一走 {@link VectorStore#similaritySearch}：元数据/版本过滤通过
 *       {@link FilterExpressionBuilder} 构造 {@link Filter.Expression}，由 PgVectorStore
 *       渲染为 PostgreSQL SQL/JSON Path 谓词并在 WHERE 内真实下推（非内存过滤）；</li>
 *   <li>映射真实分数：similarity 与 distance 分开，不编造、不自行归一化。</li>
 * </ol>
 */
@Slf4j
@Service
public class SimilaritySearchService {

    private final VectorStore vectorStore;

    private final EmbeddingModel embeddingModel;

    private final EmbeddingProperties embeddingProperties;

    private final VectorValidator vectorValidator;

    public SimilaritySearchService(VectorStore vectorStore,
                                   EmbeddingModel embeddingModel,
                                   EmbeddingProperties embeddingProperties,
                                   VectorValidator vectorValidator) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.embeddingProperties = embeddingProperties;
        this.vectorValidator = vectorValidator;
    }

    /**
     * 执行相似度检索。
     *
     * @param request 检索请求（已通过 Bean Validation，服务层再做权威归一与 fail-closed 校验）
     * @return 检索结果视图
     */
    public SimilaritySearchResultView search(SimilaritySearchRequest request) {
        String query = normalizeQuery(request.getQuery());
        int topK = normalizeTopK(request.getTopK());
        double threshold = normalizeThreshold(request.getThreshold());
        boolean filtered = hasFilter(request);

        // 1) 超长校验：长度口径为 UTF-16 code unit（字符），与 Embedding 配置一致，非 Token。
        validateLength(query);

        // 2) 显式 Query Embedding + 校验：与入库同一模型/空间；失败在此 fail-closed。
        float[] queryVector = embedAndValidate(query);

        // 3) 构造检索请求：过滤表达式由框架渲染为 SQL/JSON Path 并在 PG WHERE 内下推。
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold);
        if (filtered) {
            builder.filterExpression(buildFilterExpression(request));
        }
        List<Document> documents = vectorStore.similaritySearch(builder.build());

        // 4) 映射真实 Document 分数。
        List<SimilarityMatchView> matches = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            Double distance = toDouble(metadata == null ? null : metadata.get("distance"));
            Double similarity = toDouble(document.getScore());
            matches.add(SimilarityMatchView.from(
                    document.getId(), document.getText(), metadata, distance, similarity));
        }

        // 5) 组装结果。
        SimilaritySearchResultView view = new SimilaritySearchResultView();
        view.setQuery(query);
        view.setTopK(topK);
        view.setThreshold(threshold);
        view.setVectorSpaceId(embeddingProperties.vectorSpaceId());
        view.setFiltered(filtered);
        view.setMatches(matches);
        view.setTotal(matches.size());

        log.info("相似度检索完成: queryLength={}, topK={}, threshold={}, filtered={}, total={}, queryVectorDim={}",
                query.length(), topK, threshold, filtered, matches.size(), queryVector.length);
        return view;
    }

    /**
     * 把过滤字段构造为框架 {@link Filter.Expression}：多个条件以 AND 连接。
     */
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

    /**
     * 显式向量化并校验：调用失败、空向量、维度不符均 fail-closed。
     */
    private float[] embedAndValidate(String query) {
        float[] vector;
        try {
            vector = embeddingModel.embed(query);
        } catch (RuntimeException ex) {
            // 不回显上游细节，仅记录异常类型；对外由统一异常处理给出稳定提示。
            log.error("Query Embedding 调用失败，异常类型: {}", ex.getClass().getSimpleName());
            throw new IllegalArgumentException("查询向量化失败，请稍后重试或检查问题内容");
        }
        int expected = embeddingProperties.getDimensions();
        // 复用统一校验：非空、维度一致、无 NaN/Infinity。
        vectorValidator.validate(vector, expected);
        // 额外拦截全零向量（语义上的“空向量”，本地模型对无特征文本会保留零向量）。
        if (isZeroVector(vector)) {
            throw new IllegalArgumentException("查询向量为空向量（全零），无法进行相似度检索");
        }
        return vector;
    }

    private void validateLength(String query) {
        int max = embeddingProperties.getMaxInputLength();
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
}
