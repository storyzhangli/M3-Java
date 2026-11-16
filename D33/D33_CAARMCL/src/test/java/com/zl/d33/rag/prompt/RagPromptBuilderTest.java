package com.zl.d33.rag.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 构造器测试：证据约束与上下文/问题分隔。
 */
class RagPromptBuilderTest {

    private final RagPromptBuilder builder = new RagPromptBuilder();

    @Test
    @DisplayName("系统指令包含任务书要求的全部关键约束")
    void systemPromptShouldContainHardConstraints() {
        String system = builder.systemPrompt();
        assertTrue(system.contains("只能依据"));
        assertTrue(system.contains("证据不足"));
        assertTrue(system.contains("不得"));
        assertTrue(system.contains("伪造"));
        assertTrue(system.contains("常识"));
        assertTrue(system.contains("[S1]"));
    }

    @Test
    @DisplayName("用户消息清楚分隔检索上下文与用户问题")
    void userPromptShouldSeparateContextAndQuestion() {
        String user = builder.userPrompt("如何处理气蚀？", "[S1] 证据内容");
        assertTrue(user.contains("检索上下文"));
        assertTrue(user.contains("用户问题"));
        assertTrue(user.contains("[S1] 证据内容"));
        assertTrue(user.contains("如何处理气蚀？"));
    }
}
