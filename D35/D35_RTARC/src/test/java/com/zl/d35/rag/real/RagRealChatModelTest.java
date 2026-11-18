package com.zl.d35.rag.real;

import com.zl.d35.rag.assessment.AssessmentDecision;
import com.zl.d35.rag.model.GenerationStatus;
import com.zl.d35.rag.model.RagErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D35 真实 ChatModel 端到端测试（真实 DeepSeek，严格最小调用）。
 *
 * <p>仅当 {@code D35_REAL_CHATMODEL=true} 且会话内 {@code AI_MODE=deepseek} 时启用。
 * 本类<b>只发起 2 次真实模型调用</b>，验证真实生成下的引用契约：
 * 溯源字段来自真实检索、无虚构 URL、引用标记合法。</p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "D35_REAL_CHATMODEL", matches = "true")
class RagRealChatModelTest extends RagRealTestSupport {

    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(S\\d+)]");

    @Autowired
    private com.zl.d35.rag.orchestrator.RagOrchestrator orchestrator;

    @BeforeAll
    void setUp() {
        ensureFixtures();
    }

    @AfterAll
    void tearDown() {
        cleanupFixtures();
    }

    /** 第 1 次真实调用：接地回答，溯源字段齐全且校验通过。 */
    @Test
    void groundedAnswerSucceedsWithRealCitations() {
        var response = orchestrator.ask(request("D35设备甲的额定压力是多少？", DOC_A, 5));
        System.out.println("[real-chat] decision=" + response.getDecision()
                + " gen=" + response.getGenerationStatus() + " code=" + response.getErrorCode());
        System.out.println("[real-chat] answer=" + response.getAnswer());

        assertThat(response.getDecision()).isEqualTo(AssessmentDecision.ANSWER);
        assertThat(response.getGenerationStatus()).isEqualTo(GenerationStatus.SUCCESS);
        assertThat(response.getErrorCode()).isEqualTo(RagErrorCode.NONE);
        assertThat(response.getAnswer()).contains("0.8");

        var src = response.getSources().get(0);
        assertThat(src.getSourceId()).isEqualTo(DOC_A);
        assertThat(src.getCitationId()).isEqualTo("S1");
        assertThat(src.getPage()).isEqualTo(2);
        assertThat(src.getQuotedSnippet()).isNotBlank();
        assertThat(src.getUrl()).isNull();

        assertThat(response.getCitationValidation()).isNotNull();
        assertThat(response.getCitationValidation().isValidationPassed()).isTrue();
    }

    /** 第 2 次真实调用：多来源回答，无虚构 URL，引用标记均在来源白名单内。 */
    @Test
    void multiSourceAnswerHasNoFabricatedUrl() {
        var response = orchestrator.ask(request("D35设备乙的技术参数有哪些？", DOC_B, 5));
        System.out.println("[real-chat] multi decision=" + response.getDecision()
                + " gen=" + response.getGenerationStatus());

        assertThat(response.getDecision()).isEqualTo(AssessmentDecision.ANSWER);
        assertThat(response.getSources()).hasSize(3);
        // 契约：当前 url 恒为 null，不出现任何虚构/危险链接
        assertThat(response.getSources()).allSatisfy(s -> assertThat(s.getUrl()).isNull());

        Matcher matcher = CITATION_MARKER.matcher(
                response.getAnswer() == null ? "" : response.getAnswer());
        java.util.Set<String> used = new java.util.HashSet<>();
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        java.util.Set<String> allowed = new java.util.HashSet<>();
        response.getSources().forEach(s -> allowed.add(s.getCitationId()));
        assertThat(used).isSubsetOf(allowed);
    }
}
