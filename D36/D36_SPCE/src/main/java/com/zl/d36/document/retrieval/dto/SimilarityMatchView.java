package com.zl.d36.document.retrieval.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单条相似度命中视图：携带 Chunk 内容、来源标识与真实分数。
 *
 * <p>分数语义（关键）：</p>
 * <ul>
 *   <li>{@code similarity}：相似度，来自框架 {@code Document.getScore()}，COSINE 下
 *       由 {@code 1 - distance} 计算，越接近 1 越相似；框架未提供时如实置 null，<b>不编造</b>；</li>
 *   <li>{@code distance}：原始余弦距离（cosine distance），来自 metadata 的 {@code distance}，
 *       越小越相似；与 similarity 是同一事实的两种表达，<b>不得把 distance 当 similarity</b>。</li>
 * </ul>
 */
@Data
public class SimilarityMatchView {

    /** Chunk 标识（D31 稳定 ID，UUID v5 字符串）。 */
    private String chunkId;

    /** Chunk 文本内容。 */
    private String content;

    /** 来源文档标识（metadata.documentId）。 */
    private String documentId;

    /** 文档标题（metadata.title）。 */
    private String title;

    /** 版本（metadata.version）。 */
    private Integer version;

    /** 切分策略（metadata.strategy）。 */
    private String strategy;

    /** Chunk 在文档内序号（metadata.chunkIndex）。 */
    private Integer chunkIndex;

    /** 起始物理页（metadata.page，从 1 开始）；无定位时为 null，不推断。 */
    private Integer page;

    /** 跨页时的结束物理页（metadata.lastPage）；单页或无定位时为 null。 */
    private Integer lastPage;

    /** 起始段落序号（metadata.paragraphIndex，从 0 开始）；无定位时为 null。 */
    private Integer paragraphIndex;

    /** 真实相似度（1 - distance）；框架未提供时为 null。 */
    private Double similarity;

    /** 真实余弦距离（越小越相似）。 */
    private Double distance;

    /** 完整 metadata（含 chunkContentHash、splitVersion 等，供核对）。 */
    private Map<String, Object> metadata;

    /**
     * 由显式字段构造（框架路径与手写 SQL 路径共用）。
     *
     * @param chunkId    Chunk 标识
     * @param content    Chunk 内容
     * @param metadata   完整 metadata（会复制为 LinkedHashMap）
     * @param distance   真实距离；为 null 时 similarity 也无法计算
     * @param similarity 真实相似度；为 null 但 distance 存在时按 1 - distance 计算
     */
    public static SimilarityMatchView from(String chunkId, String content, Map<String, Object> metadata,
                                           Double distance, Double similarity) {
        SimilarityMatchView view = new SimilarityMatchView();
        view.chunkId = chunkId;
        view.content = content;
        view.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        view.distance = distance;
        // 相似度：优先用显式值；否则在距离真实存在时按 1 - distance 计算（与框架口径一致）。
        if (similarity != null) {
            view.similarity = similarity;
        } else if (distance != null) {
            view.similarity = 1.0d - distance;
        }
        view.documentId = asString(view.metadata.get("documentId"));
        view.title = asString(view.metadata.get("title"));
        view.strategy = asString(view.metadata.get("strategy"));
        view.version = asInteger(view.metadata.get("version"));
        view.chunkIndex = asInteger(view.metadata.get("chunkIndex"));
        view.page = asInteger(view.metadata.get("page"));
        view.lastPage = asInteger(view.metadata.get("lastPage"));
        view.paragraphIndex = asInteger(view.metadata.get("paragraphIndex"));
        return view;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
