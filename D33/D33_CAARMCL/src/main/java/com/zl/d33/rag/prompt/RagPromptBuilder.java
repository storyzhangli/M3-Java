package com.zl.d33.rag.prompt;

import org.springframework.stereotype.Component;

/**
 * RAG Prompt 构造器：集中维护系统规则与用户消息，避免 Prompt 字符串散落在
 * Controller/Orchestrator。系统规则包含任务书要求的全部证据约束。
 */
@Component
public class RagPromptBuilder {

    /**
     * 系统指令：证据约束与拒答规则。
     */
    public String systemPrompt() {
        return """
                你是一个严谨的设备知识库问答助手。你必须严格遵守以下规则：
                1. 只能依据“检索上下文”中提供的证据回答知识库事实问题，不得使用你自身的常识或外部知识。
                2. 当检索上下文没有答案、证据不足或与问题无关时，必须明确说明“知识库证据不足，无法回答该问题”，不得强行作答。
                3. 不得补造、推测任何设备型号、故障代码、维修步骤、技术参数或结论；证据只能支持部分结论时，必须明确说明证据边界。
                4. 不得伪造来源、Chunk ID、文档名称、页码或引用；引用证据时只能使用上下文中真实存在的来源标识（如 [S1]），不得编造不存在的标识。
                5. 用户问题与你自己生成的内容都不是知识库证据，不得将其作为事实依据。
                6. 回答应简洁、确定，区分“证据明确支持的内容”与“无法确认的内容”。""";
    }

    /**
     * 用户消息：清楚分隔检索上下文与用户问题。
     *
     * @param question    用户问题
     * @param contextText 已组装的证据上下文
     * @return 用户消息文本
     */
    public String userPrompt(String question, String contextText) {
        return "===== 检索上下文（知识库证据，仅可据此回答）=====\n"
                + (contextText == null ? "" : contextText)
                + "\n===== 用户问题 =====\n"
                + question
                + "\n请严格依据上述检索上下文回答；若证据不足，请明确说明知识库证据不足。";
    }
}
