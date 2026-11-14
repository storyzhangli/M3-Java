package com.zl.d31.document.vectorindex.model;

/**
 * 向量索引处理阶段：用于失败时定位中断点，支持部分失败恢复。
 *
 * <p>阶段顺序：{@code SPLIT -> EMBED_WRITE -> SWITCH -> CLEANUP}。
 * 状态表记录最后完成的阶段，恢复时从该阶段之后幂等续跑。</p>
 */
public enum IndexStage {

    /** 切分完成（内存内确定性计算，无外部副作用）。 */
    SPLIT,

    /** 正在/已完成 Embedding 调用与 PG 向量 Upsert。 */
    EMBED_WRITE,

    /** 新版本与旧版本的活动指针切换（MySQL 本地事务）。 */
    SWITCH,

    /** 旧版本向量与关联行清理。 */
    CLEANUP;

    public static IndexStage fromName(String name) {
        for (IndexStage stage : values()) {
            if (stage.name().equals(name)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("未知索引阶段: " + name);
    }
}
