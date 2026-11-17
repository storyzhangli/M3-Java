package com.zl.d34.rag.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RagPromptBuilder} 单元测试：验证 11 条规则、注入防护与不可信前导。
 */
class RagPromptBuilderTest {

    private final RagPromptBuilder builder = new RagPromptBuilder();

    @Test
    void systemPromptShouldContainAllElevenRules() {
        String system = builder.systemPrompt();

        for (int i = 1; i <= 11; i++) {
            assertThat(system).contains(i + ".");
        }
    }

    @Test
    void systemPromptShouldContainInjectionAndThreeClassRules() {
        String system = builder.systemPrompt();

        assertThat(system).contains("不可信");
        assertThat(system).contains("有限推断");
        assertThat(system).contains("澄清");
        assertThat(system).contains("冲突");
    }

    @Test
    void userPromptShouldFrameUntrustedContext() {
        String user = builder.userPrompt("问题", "<UNTRUSTED_EVIDENCE id=\"S1\">正文</UNTRUSTED_EVIDENCE>");

        assertThat(user).contains("不可信");
        assertThat(user).contains("UNTRUSTED_EVIDENCE");
        assertThat(user).contains("问题");
    }
}
