package com.zl.d35.rag.validation;

import com.zl.d35.rag.assessment.FactClaimExtractor;
import com.zl.d35.rag.context.EvidenceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResponseValidationService} 单元测试：非法引用、虚构 URL、无证据断言，
 * 以及 Claim 与其标注 Citation 的逐条关联（P1-02 T01–T07）。
 */
class ResponseValidationServiceTest {

    private final ResponseValidationService service =
            new ResponseValidationService(new FactClaimExtractor());

    private EvidenceItem evidence(String ref, String content) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(ref);
        item.setDocumentId("doc-1");
        item.setChunkId("chunk-" + ref);
        item.setContent(content);
        item.setTitle("设备手册");
        return item;
    }

    // ---- P1-02 T01–T07：Claim-Citation 关联 ----

    @Test
    @DisplayName("T01 Claim 由其标注的 Citation 支持：通过")
    void claimSupportedByItsOwnCitation() {
        var result = service.validate("工作压力 0.8 [S1]",
                List.of(evidence("S1", "工作压力 0.8"),
                        evidence("S2", "工作压力 0.6")));
        assertTrue(result.validationPassed());
        assertTrue(result.claimCitationMismatches().isEmpty());
    }

    @Test
    @DisplayName("T02 Claim 在别处存在，但标注的 Citation 不支持：拒绝")
    void claimExistsElsewhereButCitedSourceDoesNotSupport() {
        var result = service.validate("工作压力 0.8 [S1]",
                List.of(evidence("S1", "工作压力 0.6"),
                        evidence("S2", "工作压力 0.8")));
        assertFalse(result.validationPassed());
        assertTrue(result.unsupportedClaims().contains("工作压力=0.8"));
        assertTrue(result.claimCitationMismatches().stream()
                .anyMatch(m -> m.contains("工作压力=0.8") && m.contains("[S1]")));
    }

    @Test
    @DisplayName("T03 两来源参数值不同且模型引用错误来源：校验失败")
    void wrongSourceBetweenConflictingValues() {
        var result = service.validate("设备额定压力为 0.8 MPa。[S1]",
                List.of(evidence("S1", "设备额定压力为 0.6 MPa。"),
                        evidence("S2", "设备额定压力为 0.8 MPa。")));
        assertFalse(result.validationPassed());
        assertTrue(result.unsupportedClaims().contains("额定压力=0.8"));
    }

    @Test
    @DisplayName("T03b 同句内两值互换引用：按标记组逐条关联，不得漏判")
    void swappedCitationsInOneSentence() {
        var result = service.validate("工作压力 0.6 [S1]，工作压力 0.8 [S2]",
                List.of(evidence("S1", "工作压力 0.8"),
                        evidence("S2", "工作压力 0.6")));
        assertFalse(result.validationPassed());
        assertTrue(result.unsupportedClaims().contains("工作压力=0.6"));
        assertTrue(result.unsupportedClaims().contains("工作压力=0.8"));
    }

    @Test
    @DisplayName("T04 一个 Claim 标注多个 Citation，至少一个支持：按明确规则通过")
    void claimWithMultipleCitationsOneSupports() {
        var result = service.validate("工作压力 0.8 [S1][S2]",
                List.of(evidence("S1", "工作压力 0.6"),
                        evidence("S2", "工作压力 0.8")));
        assertTrue(result.validationPassed());
    }

    @Test
    @DisplayName("T05 多 Claim 部分引用错误：只标记错误项，不整体误判为全部正确")
    void partialClaimMismatch() {
        var result = service.validate("工作压力 0.8 [S1]，额定功率 500 [S2]",
                List.of(evidence("S1", "工作压力 0.8"),
                        evidence("S2", "工作温度 30")));
        assertFalse(result.validationPassed());
        assertTrue(result.unsupportedClaims().contains("额定功率=500"));
        assertFalse(result.unsupportedClaims().contains("工作压力=0.8"));
    }

    @Test
    @DisplayName("T06 Citation ID 不存在：拒绝该引用")
    void invalidCitationDetected() {
        var result = service.validate("结论 [S99]",
                List.of(evidence("S1", "工作压力 0.8")));
        assertFalse(result.validationPassed());
        assertTrue(result.invalidCitations().contains("[S99]"));
    }

    @Test
    @DisplayName("T07 Claim 没有引用标识：按保守策略视为无支持")
    void claimWithoutCitationRejected() {
        var result = service.validate("工作压力 0.8",
                List.of(evidence("S1", "工作压力 0.8")));
        assertFalse(result.validationPassed());
        assertTrue(result.unsupportedClaims().contains("工作压力=0.8"));
        assertTrue(result.claimCitationMismatches().stream()
                .anyMatch(m -> m.contains("未标注引用")));
    }

    @Test
    @DisplayName("T07b 句末独立引用行：相邻纯引用句的标记可被断言句继承")
    void citationOnFollowingLineIsInherited() {
        var result = service.validate("工作压力 0.8。\n[S1]",
                List.of(evidence("S1", "工作压力 0.8")));
        assertTrue(result.validationPassed());
    }

    // ---- 既有回归用例 ----

    @Test
    @DisplayName("合法引用且事实被证据支持：通过")
    void cleanAnswerPasses() {
        var result = service.validate("工作压力 0.8 [S1]",
                List.of(evidence("S1", "工作压力 0.8")));
        assertTrue(result.validationPassed());
        assertTrue(result.fabricatedUrls().isEmpty());
    }

    @Test
    @DisplayName("危险协议与无来源 http 链接被标记为虚构 URL")
    void fabricatedUrlDetected() {
        var dangerous = service.validate("见 javascript:alert(1)",
                List.of(evidence("S1", "工作压力 0.8")));
        assertFalse(dangerous.validationPassed());
        assertTrue(dangerous.fabricatedUrls().stream().anyMatch(u -> u.startsWith("javascript:")));

        var plainHttp = service.validate("参考 https://other.example.com/x",
                List.of(evidence("S1", "工作压力 0.8")));
        assertTrue(plainHttp.fabricatedUrls().contains("https://other.example.com/x"));
    }

    @Test
    @DisplayName("本机绝对路径被标记为虚构 URL")
    void localPathDetected() {
        var result = service.validate("文件在 C:\\Users\\zl\\a.txt",
                List.of(evidence("S1", "工作压力 0.8")));
        assertTrue(result.fabricatedUrls().contains("C:\\Users\\zl\\a.txt"));
    }

    @Test
    @DisplayName("无证据支持的参数断言被标记为 UNSUPPORTED")
    void unsupportedClaimDetected() {
        var result = service.validate("额定功率 500 [S1]",
                List.of(evidence("S1", "工作压力 0.8")));
        assertFalse(result.validationPassed());
        assertTrue(result.unsupportedClaims().contains("额定功率=500"));
    }

    @Test
    @DisplayName("空回答：直接通过，不产生误报")
    void blankAnswerPasses() {
        var result = service.validate("   ", List.of(evidence("S1", "工作压力 0.8")));
        assertTrue(result.validationPassed());
        assertEquals(0, result.claimCitationMismatches().size());
    }
}
