package com.zl.d41.rag.model;

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
    LIFECYCLE_LOOKUP_FAILED,

    /** 响应前权威核验发现目标文档/Chunk 已被删除（覆盖检索后的竞态窗口）。 */
    CITATION_TARGET_DELETED,

    /**
     * 响应前权威核验发现 Chunk 归属与证据声明的 documentId 不一致，
     * 或 Chunk 行的 documentId 缺失、无法证明归属；不得仅因二者分别存在就判定引用有效。
     */
    CITATION_OWNERSHIP_MISMATCH,

    /** 页码定位缺失；page 为正常可空字段，不补造、不单独作为失败，仅用于标记与统计。 */
    PAGE_LOCATION_MISSING,

    /** 模型输出包含危险协议/本机路径或无来源支撑的虚构 URL。 */
    FABRICATED_URL,

    /** 响应前核验发现目标文档未生效或已废弃（DRAFT/DEPRECATED/UNKNOWN 等）。 */
    EVIDENCE_NOT_EFFECTIVE
}
