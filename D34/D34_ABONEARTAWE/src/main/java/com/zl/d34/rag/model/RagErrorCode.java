package com.zl.d34.rag.model;

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
    GENERATION_FAILED,

    /** 仅关键词相似，证据不能支持问题。 */
    LOW_RELEVANCE,

    /** 有相关证据但覆盖度不足。 */
    INSUFFICIENT_EVIDENCE,

    /** 缺设备型号/故障代码等必要条件，需澄清。 */
    MISSING_REQUIRED_CONTEXT,

    /** 有效证据对同一事实给出矛盾内容。 */
    EVIDENCE_CONFLICT,

    /** 引用不在本次来源白名单或格式非法。 */
    INVALID_CITATION,

    /** 输出含无证据支持的设备参数/故障码/维修步骤。 */
    UNSUPPORTED_CLAIM,

    /** 生成后校验未通过的受控失败。 */
    GENERATION_VALIDATION_FAILED,

    /** 生命周期查询失败（基础设施故障），无法确认证据有效性，受控拒答而非默认放行。 */
    LIFECYCLE_LOOKUP_FAILED
}
