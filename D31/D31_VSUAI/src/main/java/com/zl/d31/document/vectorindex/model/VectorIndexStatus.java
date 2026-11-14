package com.zl.d31.document.vectorindex.model;

/**
 * 文档向量索引状态机。
 *
 * <p>流转：{@code PENDING -> INDEXING -> COMPLETED}；
 * 异常收敛到 {@code PARTIAL}（已有部分向量写入）或 {@code FAILED}（尚无有效写入）；
 * 文档新版本切换后，旧版本置 {@code SUPERSEDED}。</p>
 */
public enum VectorIndexStatus {

    /** 已建立索引任务，尚未开始写入。 */
    PENDING,

    /** 正在切分/向量化/写入。 */
    INDEXING,

    /** 该版本全部 Chunk 向量写入并核对通过。 */
    COMPLETED,

    /** 部分 Chunk 已写入，存在未完成部分，可幂等恢复。 */
    PARTIAL,

    /** 写入失败，记录错误码与阶段，可重试。 */
    FAILED,

    /** 已被同文档新版本取代，其向量等待清理。 */
    SUPERSEDED;

    public static VectorIndexStatus fromName(String name) {
        for (VectorIndexStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知向量索引状态: " + name);
    }
}
