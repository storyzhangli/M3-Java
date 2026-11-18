package com.zl.d35.rag.validation;

import com.zl.d35.rag.assessment.FactClaimExtractor;
import com.zl.d35.rag.context.EvidenceItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResponseValidationService} 单元测试：非法引用、虚构 URL 与无证据断言。
 */
class ResponseValidationServiceTest {

    private final ResponseValidationService service =
            new ResponseValidationService(new FactClaimExtractor());

    private EvidenceItem evidence(String ref, String content) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(ref);
        item.setDocumentId("doc-1");
        item.setChunkId("chunk-1");
        item.setContent(content);
        item.setTitle("设备手册");
        return item;
    }

    @Test
    @DisplayName("合法引用且事实被证据支持：通过")
    void cleanAnswerPasses() {
        var result = service.validate("工作压力 0.8 [S1]",
                List.of(evidence("S1", "工作压力 0.8")));
        assertTrue(result.validationPassed());
        assertTrue(result.fabricatedUrls().isEmpty());
    }

    @Test
    @DisplayName("未知引用 [S99] 被标记为非法")
    void invalidCitationDetected() {
        var result = service.validate("结论 [S99]",
                List.of(evidence("S1", "工作压力 0.8")));
        assertFalse(result.validationPassed());
        assertTrue(result.invalidCitations().contains("[S99]"));
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
}
