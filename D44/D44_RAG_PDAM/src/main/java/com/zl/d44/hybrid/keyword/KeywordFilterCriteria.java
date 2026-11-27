package com.zl.d44.hybrid.keyword;

import com.zl.d44.access.PermissionScope;

/**
 * 关键词路元数据过滤条件：与向量路（FilterExpressionBuilder：documentId/title/version/strategy
 * AND 组合、SQL 下推）保持同一口径；在倒排侧按 chunk 显式字段过滤（先过滤后排名，
 * 等价于向量路"过滤在 Top-K 之前"的语义）。null/空白字段 = 不限制。
 *
 * <p>P2 追加：{@code permission} 组件携带服务端权限代数（{@link PermissionScope}，
 * 与向量路同一实现源，防双实现漂移）；null = 不做权限过滤（enabled=false 零差异路径，H1）。
 * 非 null 时逐 chunk 调 {@link PermissionScope#matches}——缺失键 fail-closed（D3）。</p>
 */
public record KeywordFilterCriteria(String documentId, String title, Integer version, String strategy,
                                    PermissionScope permission) {

    /** D38 兼容工厂：四元业务条件，无权限过滤。 */
    public KeywordFilterCriteria(String documentId, String title, Integer version, String strategy) {
        this(documentId, title, version, strategy, null);
    }

    public boolean isEmpty() {
        return isBlank(documentId) && isBlank(title) && version == null && isBlank(strategy)
                && permission == null;
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
        if (!isBlank(strategy) && !strategy.trim().equals(chunk.strategy())) {
            return false;
        }
        // 权限过滤先于排名/Top-K（H4）；代数判定与向量路谓词等价（单测对拍）
        return permission == null
                || permission.matches(chunk.tenantId(), chunk.deptId(), chunk.deviceId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 过滤所需 chunk 显式字段（非 Map，H8/用户契约：核心契约禁用无约束 Map）。 */
    public record ChunkFields(String documentId, String title, Integer version, String strategy,
                              String tenantId, String deptId, String deviceId) {

        /** D38 兼容工厂：业务四字段，权限三字段缺省 null。 */
        public ChunkFields(String documentId, String title, Integer version, String strategy) {
            this(documentId, title, version, strategy, null, null, null);
        }
    }
}
