package com.zl.d33.rag.model;

/**
 * RAG 稳定错误码（对外契约，不随上游报文变化）。
 */
public enum RagErrorCode {

    /** 无错误。 */
    NONE,

    /** 问题参数非法（空白/超长）。 */
    INVALID_QUESTION,

    /** 检索执行失败。 */
    RETRIEVAL_FAILED,

    /** 无可用证据（含空检索）。 */
    NO_EVIDENCE,

    /** 模型未配置/不可用。 */
    MODEL_NOT_CONFIGURED,

    /** 模型调用超时。 */
    GENERATION_TIMEOUT,

    /** 模型返回空响应。 */
    EMPTY_RESPONSE,

    /** 模型调用其他异常。 */
    GENERATION_FAILED
}
