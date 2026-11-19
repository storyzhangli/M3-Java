package com.zl.d36.document.retrieval.dto;

import com.zl.d36.document.retrieval.dto.SimilarityMatchView;
import lombok.Data;

import java.util.List;

/**
 * 相似度检索结果视图：回显请求口径、向量空间标识与命中列表。
 *
 * <p>不包含任何大段向量或敏感信息；{@code vectorSpaceId} 用于核对查询与入库
 * 是否处于同一模型/版本/维度空间。</p>
 */
@Data
public class SimilaritySearchResultView {

    /** 原始用户问题。 */
    private String query;

    /** 实际生效的 Top-K（已归一与边界处理）。 */
    private int topK;

    /** 实际生效的相似度阈值（similarity 语义）。 */
    private double threshold;

    /** 查询向量空间标识（provider|model|modelVersion|dimensions）。 */
    private String vectorSpaceId;

    /** 是否启用了元数据/版本过滤（true 表示至少一个过滤条件）。 */
    private boolean filtered;

    /** 命中条数。 */
    private int total;

    /** 命中列表（按相似度降序/距离升序）。 */
    private List<SimilarityMatchView> matches;

    /** D36 实验计时：Query Embedding 段耗时（毫秒，nanoTime 换算，保留 3 位小数）。 */
    private Double queryEmbeddingMs;

    /** D36 实验计时：VectorStore 检索段耗时（毫秒，含阈值过滤与结果映射前的库内开销）。 */
    private Double vectorSearchMs;

    /** D36 实验计时：检索总耗时（毫秒）= Embedding + 检索 + 结果后处理；不含生成/网络传输。 */
    private Double retrievalTotalMs;
}
