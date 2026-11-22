package com.zl.d39.rag.assessment;

/**
 * 证据评估建议动作。
 *
 * <ul>
 *   <li>{@link #ANSWER}：证据充分，允许进入生成；</li>
 *   <li>{@link #CLARIFY}：缺必要条件，先澄清；</li>
 *   <li>{@link #REFUSE}：无证据/低相关/覆盖不足，受控拒答；</li>
 *   <li>{@link #CONFLICT}：有效证据冲突，等待权威依据或人工确认。</li>
 * </ul>
 */
public enum AssessmentDecision {
    ANSWER,
    CLARIFY,
    REFUSE,
    CONFLICT
}
