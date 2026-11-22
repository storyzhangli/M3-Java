package com.zl.d39.hybrid.keyword;

/**
 * 关键词路元数据过滤条件：与向量路（FilterExpressionBuilder：documentId/title/version/strategy
 * AND 组合、SQL 下推）保持同一口径；在倒排侧按 chunk 显式字段过滤（先过滤后排名，
 * 等价于向量路"过滤在 Top-K 之前"的语义）。null/空白字段 = 不限制。
 */
public record KeywordFilterCriteria(String documentId, String title, Integer version, String strategy) {

    public boolean isEmpty() {
        return isBlank(documentId) && isBlank(title) && version == null && isBlank(strategy);
    }

    /** 单 chunk 是否通过全部已给条件（AND）。 */
    public boolean matches(ChunkFields chunk) {
        if (!isBlank(documentId) && !documentId.trim().equals(chunk.documentId())) {
            return false;
        }
        if (!isBlank(title) && !title.trim().equals(chunk.title())) {
            return false;
        }
        if (version != null && !version.equals(chunk.version())) {
            return false;
        }
        return !(!isBlank(strategy) && !strategy.trim().equals(chunk.strategy()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 过滤所需 chunk 显式字段（非 Map，H8/用户契约：核心契约禁用无约束 Map）。 */
    public record ChunkFields(String documentId, String title, Integer version, String strategy) {
    }
}
