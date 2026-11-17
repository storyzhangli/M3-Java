package com.zl.d34.rag.assessment;

import com.zl.d34.device.ClarificationService;
import com.zl.d34.document.model.DocumentLifecycleStatus;
import com.zl.d34.rag.context.EvidenceItem;
import com.zl.d34.rag.model.RagErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生命周期 Fail-closed 评估级测试：验证实际决策结果而非仅方法返回。
 *
 * <p>覆盖 T07（UNKNOWN 非有效）、T08（混合证据中查询失败的一条被拒、其余有效可继续），
 * 以及全量 LOOKUP_FAILED / NOT_FOUND 的错误码分流，确认非 ANSWER 路径不允许生成。</p>
 */
class LifecycleFailClosedAssessmentTest {

    private final Map<String, DocumentLifecycleStatus> lifecycleMap = new HashMap<>();
    private EvidenceAssessmentService service;

    @BeforeEach
    void setUp() {
        DocumentLifecyclePort port = id ->
                lifecycleMap.getOrDefault(id, DocumentLifecycleStatus.ACTIVE);
        service = new EvidenceAssessmentService(
                port,
                new FactClaimExtractor(),
                new EvidenceAssessmentProperties(),
                new ClarificationService());
    }

    /** T07：UNKNOWN 状态非有效，受控拒答且不允许生成。 */
    @Test
    void t07_unknownIsRejectedAndRefuses() {
        lifecycleMap.put("doc-unknown", DocumentLifecycleStatus.UNKNOWN);
        EvidenceItem item = item("S1", "doc-unknown", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "A型水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.NO_EVIDENCE);
        assertThat(result.rejectedEvidenceIds()).containsExactly("S1");
        assertThat(result.allowsGeneration()).isFalse();
    }

    /** 全部 LOOKUP_FAILED：明确返回 LIFECYCLE_LOOKUP_FAILED，不隐藏基础设施故障。 */
    @Test
    void allLookupFailedReturnsLifecycleErrorCode() {
        lifecycleMap.put("doc-fail", DocumentLifecycleStatus.LOOKUP_FAILED);
        EvidenceItem item = item("S1", "doc-fail", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "A型水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.LIFECYCLE_LOOKUP_FAILED);
        assertThat(result.allowsGeneration()).isFalse();
    }

    /** 全部 NOT_FOUND：业务上无有效证据，返回 NO_EVIDENCE，不默认 ACTIVE。 */
    @Test
    void allNotFoundReturnsNoEvidence() {
        lifecycleMap.put("doc-missing", DocumentLifecycleStatus.NOT_FOUND);
        EvidenceItem item = item("S1", "doc-missing", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "A型水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.NO_EVIDENCE);
        assertThat(result.allowsGeneration()).isFalse();
    }

    /**
     * T08：两条证据中一条查询失败（S1），该条被拒且不静默当有效；
     * 另一条 ACTIVE（S2）有效，决策仍可基于 S2 进入 ANSWER，不触发冲突。
     */
    @Test
    void t08_oneLookupFailedRejectedOtherActiveContinues() {
        lifecycleMap.put("doc-fail", DocumentLifecycleStatus.LOOKUP_FAILED);
        EvidenceItem failed = item("S1", "doc-fail", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);
        EvidenceItem good = item("S2", "doc-good", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(failed, good), "A型水泵的额定压力是多少");

        assertThat(result.rejectedEvidenceIds()).containsExactly("S1");
        assertThat(result.acceptedEvidenceIds()).containsExactly("S2");
        assertThat(result.conflictGroups()).isEmpty();
        assertThat(result.decision()).isEqualTo(AssessmentDecision.ANSWER);
        assertThat(result.allowsGeneration()).isTrue();
    }

    private EvidenceItem item(String ref, String docId, String title,
                              String content, Double similarity) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(ref);
        item.setDocumentId(docId);
        item.setTitle(title);
        item.setContent(content);
        item.setSimilarity(similarity);
        return item;
    }
}
