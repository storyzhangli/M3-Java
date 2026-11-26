package com.zl.d43.rag.assessment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 证据评估配置（前缀 {@code app.rag.assessment}）。
 *
 * <p><b>口径声明</b>：以下阈值为工程默认起点，<b>非经实测标定的承诺值</b>；
 * 相似度仅作检索信号，不能单独证明证据充分，真实项目应结合评测集调整。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.rag.assessment")
public class EvidenceAssessmentProperties {

    /** 低相关相似度阈值：低于该值（且相似度非 null）标记为低相关信号。默认 0.55，仅为起点。 */
    private double lowRelevanceSimilarityThreshold = 0.55d;

    /** 问题术语覆盖率下限：低于该值判定覆盖不足。默认 0.60，仅为起点。 */
    private double minQuestionTermCoverage = 0.60d;

    /** 当问题跨多个设备主体且未指明型号时，是否触发澄清。默认开启。 */
    private boolean clarifyOnAmbiguousSubject = true;

    /** 触发“型号歧义澄清”的不同主体数下限。默认 2。 */
    private int ambiguousSubjectCount = 2;
}
