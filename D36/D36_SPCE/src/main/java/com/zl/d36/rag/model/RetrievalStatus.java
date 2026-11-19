package com.zl.d36.rag.model;

/**
 * 检索阶段状态。
 *
 * <ul>
 *   <li>{@link #SUCCESS}：检索执行成功且有命中；</li>
 *   <li>{@link #EMPTY}：检索执行成功但无命中（与系统故障区分）；</li>
 *   <li>{@link #FAILED}：检索执行失败（向量化/向量库异常）。</li>
 * </ul>
 */
public enum RetrievalStatus {
    SUCCESS,
    EMPTY,
    FAILED
}
