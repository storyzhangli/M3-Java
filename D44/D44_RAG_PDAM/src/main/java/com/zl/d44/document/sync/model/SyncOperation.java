package com.zl.d44.document.sync.model;

/**
 * 同步操作类型（KP4 冻结，与 DDL {@code ENUM('DELETE','UPDATE_CLEANUP')} 一致）。
 *
 * <ul>
 *   <li>{@link #DELETE}：文档删除闭环（tombstone 标记 → PG 清理 → 关联清理 → 双端确认），
 *       version 固定 0（全版本语义）；</li>
 *   <li>{@link #UPDATE_CLEANUP}：v1→v2 更新后的旧版本清理（SWITCH 后 SUPERSEDED 版本向量与
 *       关联行清除），version=目标旧版本号。</li>
 * </ul>
 */
public enum SyncOperation {

    /** 文档删除（全版本）。 */
    DELETE,

    /** 更新后旧版本清理。 */
    UPDATE_CLEANUP;

    public static SyncOperation fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (SyncOperation operation : values()) {
            if (operation.name().equals(name.trim())) {
                return operation;
            }
        }
        return null;
    }

    /** DELETE 操作的全版本语义版本哨兵（冻结：version=0，KP4 裁决 1）。 */
    public static final int ALL_VERSIONS = 0;

    /** 归一化：按操作类型给出落库 version 值（DELETE 恒 0；UPDATE_CLEANUP 必须 &gt;0）。 */
    public int normalizeVersion(Integer version) {
        if (this == DELETE) {
            return ALL_VERSIONS;
        }
        if (version == null || version <= 0) {
            throw new IllegalArgumentException("UPDATE_CLEANUP 必须携带正版本号，got=" + version);
        }
        return version;
    }
}
