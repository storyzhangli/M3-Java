package com.zl.d42.rag.rerank;

/**
 * 精排结果标记（KP4 异常码表组成部分，实验记录与响应观测共用）。
 *
 * <p>标记语义互不排斥，一次结果可携带多个（如 REORDERED + TRUNCATED）。</p>
 */
public enum RerankFlags {

    /** 恒等输出（noop 策略或单候选）。 */
    IDENTITY,

    /** 真实重排且顺序发生变化。 */
    REORDERED,

    /** 空候选：未执行任何打分/调用。 */
    NOOP_EMPTY,

    /** 输出被 max-output 截断（A3 截断臂）。 */
    OUTPUT_TRUNCATED,

    /** 单文档估算 token 超 per-doc 上限，输入侧截断（E4/TRUNCATED）。 */
    TRUNCATED,

    /** 请求估算总 token 超上限：预检拒绝并回退原序（E4/OVERSIZED）。 */
    OVERSIZED,

    /** 任何失败回退原始向量序（H8 fail-open；检索状态不受影响）。 */
    FAILED_OPEN,

    /** 瞬时错误已执行应用层单次重试（E2；仍失败则伴随 FAILED_OPEN）。 */
    RETRIED,

    /** 远程预算三重帽触顶：本调用不发远程请求，回退原序（E5/熔断#6）。 */
    BUDGET_STOP
}
