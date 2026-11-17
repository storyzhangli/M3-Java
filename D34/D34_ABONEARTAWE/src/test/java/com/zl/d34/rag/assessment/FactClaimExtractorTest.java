package com.zl.d34.rag.assessment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FactClaimExtractor} 单元测试：覆盖结构化参数抽取、长标签优先与数值归一化。
 */
class FactClaimExtractorTest {

    private final FactClaimExtractor extractor = new FactClaimExtractor();

    @Test
    void shouldExtractParameterAndValue() {
        List<FactClaim> claims = extractor.extract("A型水泵", "额定压力为 0.8 MPa", "S1");

        assertThat(claims).hasSize(1);
        FactClaim claim = claims.get(0);
        assertThat(claim.subject()).isEqualTo("A型水泵");
        assertThat(claim.parameter()).isEqualTo("额定压力");
        assertThat(claim.value()).isEqualTo("0.8");
        assertThat(claim.sourceRef()).isEqualTo("S1");
    }

    @Test
    void shouldNormalizeTrailingZeros() {
        List<FactClaim> claims = extractor.extract("A型水泵", "额定压力：0.60", "S1");

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).value()).isEqualTo("0.6");
    }

    @Test
    void shouldPreferLongParameterLabel() {
        List<FactClaim> claims = extractor.extract("A型水泵", "额定压力 1.0", "S1");

        assertThat(claims).hasSize(1);
        assertThat(claims.get(0).parameter()).isEqualTo("额定压力");
    }

    @Test
    void shouldExtractMultipleClaims() {
        List<FactClaim> claims = extractor.extract(
                "A型水泵", "流量 120 m³/h，温度 85℃", "S1");

        assertThat(claims).hasSize(2);
        assertThat(claims).extracting(FactClaim::parameter)
                .containsExactly("流量", "温度");
        assertThat(claims).extracting(FactClaim::value)
                .containsExactly("120", "85");
    }

    @Test
    void shouldReturnEmptyForBlankText() {
        assertThat(extractor.extract("A型水泵", "   ", "S1")).isEmpty();
        assertThat(extractor.extract("A型水泵", null, "S1")).isEmpty();
    }

    @Test
    void factKeyShouldCombineSubjectAndParameter() {
        FactClaim claim = new FactClaim("A型水泵", "压力", "0.8", "S1");
        assertThat(claim.factKey()).isEqualTo("A型水泵|压力");
    }
}
