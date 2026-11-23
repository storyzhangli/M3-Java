package com.zl.d40.rag.assessment;

import com.zl.d40.rag.assessment.AssessmentDecision;
import com.zl.d40.rag.assessment.ConflictGroup;
import com.zl.d40.rag.model.RagErrorCode;

import java.util.List;

/**
 * 证据评估结果（任务书要求的 9 字段，不可只返回 hasEvidence 布尔值）。
 *
 * @param decision            建议动作 ANSWER/CLARIFY/REFUSE/CONFLICT
 * @param reasonCode          稳定原因码（接入统一 {@link RagErrorCode}）
 * @param reason              人类可读说明；CLARIFY 时为单条高价值澄清问题
 * @param evidenceIds         全部参与评估的来源标识
 * @param acceptedEvidenceIds 经生命周期/相关性接受的来源标识
 * @param rejectedEvidenceIds 被拒绝的来源标识（未生效/废弃/低相关）
 * @param conflictGroups      冲突组
 * @param missingInformation  缺失信息清单
 * @param assessmentDetails   逐条评估明细（可解释、可追溯）
 */
public record EvidenceAssessmentResult(
        AssessmentDecision decision,
        RagErrorCode reasonCode,
        String reason,
        List<String> evidenceIds,
        List<String> acceptedEvidenceIds,
        List<String> rejectedEvidenceIds,
        List<ConflictGroup> conflictGroups,
        List<String> missingInformation,
        List<String> assessmentDetails) {

    public boolean allowsGeneration() {
        return decision == AssessmentDecision.ANSWER;
    }
}
