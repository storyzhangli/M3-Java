package com.zl.d39.rag.dto;

import com.zl.d39.rag.assessment.AssessmentDecision;
import com.zl.d39.rag.dto.CitationValidationView;
import com.zl.d39.rag.dto.ConflictGroupView;
import com.zl.d39.rag.dto.RagSourceView;
import com.zl.d39.rag.dto.RagTimingView;
import com.zl.d39.rag.model.GenerationStatus;
import com.zl.d39.rag.model.RagErrorCode;
import com.zl.d39.rag.model.RetrievalStatus;
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

    /** 证据评估建议动作（ANSWER/CLARIFY/REFUSE/CONFLICT）。 */
    private AssessmentDecision decision;

    /** 单条澄清问题（CLARIFY 时返回，否则为 null）。 */
    private String clarificationQuestion;

    /** 缺失信息清单。 */
    private List<String> missingInformation = new ArrayList<>();

    /** 冲突组视图。 */
    private List<ConflictGroupView> conflictGroups = new ArrayList<>();

    /** 被拒绝的来源标识（未生效/废弃/低相关）。 */
    private List<String> rejectedEvidenceIds = new ArrayList<>();

    /** 生成后引用与事实支持校验视图。 */
    private CitationValidationView citationValidation;

    /** 引用核验说明：被剔除来源及原因、页码缺失覆盖情况等；无内容时为空。 */
    private List<String> citationNotes = new ArrayList<>();
}
