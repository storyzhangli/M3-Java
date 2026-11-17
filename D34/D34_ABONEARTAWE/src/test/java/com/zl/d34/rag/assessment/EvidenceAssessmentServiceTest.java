package com.zl.d34.rag.assessment;

import com.zl.d34.device.ClarificationService;
import com.zl.d34.document.model.DocumentLifecycleStatus;
import com.zl.d34.rag.context.EvidenceItem;
import com.zl.d34.rag.model.RagErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EvidenceAssessmentService} 单元测试：确定性决策优先级与冲突/生命周期/低相关/澄清。
 */
class EvidenceAssessmentServiceTest {

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

    @Test
    void shouldAnswerWhenSingleRelevantActiveEvidence() {
        EvidenceItem item = item("S1", "doc1", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.82);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "A型水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.ANSWER);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.NONE);
        assertThat(result.allowsGeneration()).isTrue();
        assertThat(result.acceptedEvidenceIds()).containsExactly("S1");
    }

    @Test
    void shouldRefuseNoEvidenceWhenEmpty() {
        EvidenceAssessmentResult result = service.assess(new ArrayList<>(), "任意问题");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.NO_EVIDENCE);
        assertThat(result.allowsGeneration()).isFalse();
    }

    @Test
    void shouldRejectDeprecatedAndRefuse() {
        lifecycleMap.put("doc-old", DocumentLifecycleStatus.DEPRECATED);
        EvidenceItem item = item("S1", "doc-old", "A型水泵",
                "A型水泵额定压力为 0.6 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "A型水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.NO_EVIDENCE);
        assertThat(result.rejectedEvidenceIds()).containsExactly("S1");
    }

    @Test
    void shouldRejectDraftEvenWithHighVersion() {
        lifecycleMap.put("doc-draft", DocumentLifecycleStatus.DRAFT);
        EvidenceItem item = item("S1", "doc-draft", "A型水泵",
                "A型水泵额定压力为 0.9 MPa", 0.9);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "A型水泵的额定压力是多少");

        assertThat(result.rejectedEvidenceIds()).containsExactly("S1");
        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
    }

    @Test
    void shouldRefuseLowRelevance() {
        EvidenceItem item = item("S1", "doc1", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.20);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "A型水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.LOW_RELEVANCE);
    }

    @Test
    void shouldClarifyWhenAmbiguousAcrossSubjects() {
        EvidenceItem a = item("S1", "doc1", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);
        EvidenceItem b = item("S2", "doc2", "B型水泵",
                "B型水泵额定压力为 1.0 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(a, b), "水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.CLARIFY);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.MISSING_REQUIRED_CONTEXT);
        assertThat(result.reason()).contains("型号");
        assertThat(result.missingInformation()).contains("设备型号");
    }

    @Test
    void shouldConflictWhenTwoActiveContradictSameSubject() {
        EvidenceItem a = item("S1", "doc1", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);
        EvidenceItem b = item("S2", "doc2", "A型水泵",
                "A型水泵额定压力为 0.6 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(a, b), "A型水泵的额定压力是多少");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.CONFLICT);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.EVIDENCE_CONFLICT);
        assertThat(result.conflictGroups()).hasSize(1);
        ConflictGroup group = result.conflictGroups().get(0);
        assertThat(group.parameter()).isEqualTo("额定压力");
        assertThat(group.values()).containsExactlyInAnyOrder("0.8", "0.6");
        assertThat(group.evidenceIds()).containsExactlyInAnyOrder("S1", "S2");
    }

    @Test
    void shouldNotConflictWhenDifferentDeviceModels() {
        EvidenceItem a = item("S1", "doc1", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);
        EvidenceItem b = item("S2", "doc2", "B型水泵",
                "B型水泵额定压力为 1.0 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(a, b), "水泵的额定压力是多少");

        assertThat(result.conflictGroups()).isEmpty();
        assertThat(result.decision()).isEqualTo(AssessmentDecision.CLARIFY);
    }

    @Test
    void shouldFilterDeprecatedThenAnswerWithoutConflict() {
        lifecycleMap.put("doc-old", DocumentLifecycleStatus.DEPRECATED);
        EvidenceItem old = item("S1", "doc-old", "A型水泵",
                "A型水泵额定压力为 0.6 MPa", 0.8);
        EvidenceItem current = item("S2", "doc-new", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(old, current), "A型水泵的额定压力是多少");

        assertThat(result.rejectedEvidenceIds()).containsExactly("S1");
        assertThat(result.acceptedEvidenceIds()).containsExactly("S2");
        assertThat(result.conflictGroups()).isEmpty();
        assertThat(result.decision()).isEqualTo(AssessmentDecision.ANSWER);
    }

    @Test
    void shouldRefuseWhenCoverageInsufficient() {
        EvidenceItem item = item("S1", "doc1", "A型水泵",
                "A型水泵额定压力为 0.8 MPa", 0.8);

        EvidenceAssessmentResult result =
                service.assess(List.of(item), "B型水泵的维修步骤是什么");

        assertThat(result.decision()).isEqualTo(AssessmentDecision.REFUSE);
        assertThat(result.reasonCode()).isEqualTo(RagErrorCode.INSUFFICIENT_EVIDENCE);
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
