package com.zl.d34.rag.validation;

import com.zl.d34.rag.assessment.FactClaimExtractor;
import com.zl.d34.rag.context.EvidenceItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResponseValidationService} 单元测试：区分引用 ID 合法性与事实支持关系。
 */
class ResponseValidationServiceTest {

    private final ResponseValidationService service =
            new ResponseValidationService(new FactClaimExtractor());

    @Test
    void shouldPassWhenCitationLegalAndClaimSupported() {
        EvidenceItem evidence = item("S1", "A型水泵", "A型水泵额定压力为 0.8 MPa");
        String answer = "A型水泵额定压力为 0.8 MPa [S1]";

        CitationValidationResult result = service.validate(answer, List.of(evidence));

        assertThat(result.validationPassed()).isTrue();
        assertThat(result.legalCitationPassed()).isTrue();
        assertThat(result.invalidCitations()).isEmpty();
        assertThat(result.unsupportedClaims()).isEmpty();
    }

    @Test
    void shouldDetectUnknownCitation() {
        EvidenceItem evidence = item("S1", "A型水泵", "A型水泵额定压力为 0.8 MPa");
        String answer = "A型水泵额定压力为 0.8 MPa [S99]";

        CitationValidationResult result = service.validate(answer, List.of(evidence));

        assertThat(result.legalCitationPassed()).isFalse();
        assertThat(result.invalidCitations()).containsExactly("[S99]");
        assertThat(result.validationPassed()).isFalse();
    }

    @Test
    void shouldDetectUnsupportedClaimEvenWithLegalCitation() {
        EvidenceItem evidence = item("S1", "A型水泵", "A型水泵额定压力为 0.8 MPa");
        // 引用合法，但参数值 0.9 与证据 0.8 不一致
        String answer = "A型水泵额定压力为 0.9 MPa [S1]";

        CitationValidationResult result = service.validate(answer, List.of(evidence));

        assertThat(result.legalCitationPassed()).isTrue();
        assertThat(result.unsupportedClaims()).containsExactly("额定压力=0.9");
        assertThat(result.validationPassed()).isFalse();
    }

    @Test
    void shouldReturnPassForBlankAnswer() {
        EvidenceItem evidence = item("S1", "A型水泵", "A型水泵额定压力为 0.8 MPa");
        assertThat(service.validate("   ", List.of(evidence)).validationPassed()).isTrue();
    }

    @Test
    void staticPassFactoryShouldBeValid() {
        CitationValidationResult pass = CitationValidationResult.pass();
        assertThat(pass.validationPassed()).isTrue();
        assertThat(pass.legalCitationPassed()).isTrue();
    }

    private EvidenceItem item(String ref, String title, String content) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(ref);
        item.setTitle(title);
        item.setContent(content);
        item.setDocumentId("doc1");
        return item;
    }
}
