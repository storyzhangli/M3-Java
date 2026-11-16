package com.zl.d33.rag.dto;

import com.zl.d33.rag.model.GenerationStatus;
import com.zl.d33.rag.model.RagErrorCode;
import com.zl.d33.rag.model.RetrievalStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 对外响应：回答、来源、检索/生成状态、错误码与可选预算/耗时统计。
 */
@Data
public class RagAnswerResponse {

    /** 回答内容；受控失败或拒答时可能为 null 或受控提示。 */
    private String answer;

    /** 提供给模型的证据来源列表。 */
    private List<RagSourceView> sources = new ArrayList<>();

    /** 检索状态。 */
    private RetrievalStatus retrievalStatus;

    /** 生成状态。 */
    private GenerationStatus generationStatus;

    /** 稳定错误码。 */
    private RagErrorCode errorCode = RagErrorCode.NONE;

    /** 对外安全错误说明（不含堆栈/上游报文/密钥）。 */
    private String errorMessage;

    /** 检索命中条数。 */
    private int retrievedCount;

    /** 最终进入上下文的证据条数。 */
    private int evidenceCount;

    /** 预算统计。 */
    private RagBudgetView budget;

    /** 耗时统计。 */
    private RagTimingView timing;

    /** 模型回答是否引用了上下文之外的未知来源标识。 */
    private boolean unknownCitationDetected;
}
