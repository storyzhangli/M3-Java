package com.zl.d31.document.vectorindex.model;

/**
 * Chunk 关联行状态。
 */
public enum ChunkRowStatus {

    /** 关联行已建立且对应向量已写入（或已确认存在）。 */
    INDEXED,

    /** 该 Chunk 向量写入失败，等待重试。 */
    FAILED;

    public static ChunkRowStatus fromName(String name) {
        for (ChunkRowStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知 Chunk 关联状态: " + name);
    }
}
