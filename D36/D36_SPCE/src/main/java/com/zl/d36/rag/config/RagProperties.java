package com.zl.d36.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 配置（前缀 {@code app.rag}）。
 *
 * <p><b>预算单位口径（关键）</b>：本工程无 Tokenizer，不具备真实 Token 计数能力，
 * 因此上下文预算以 <b>UTF-16 字符（code unit）</b>为单位，{@code budgetUnit} 固定为
 * {@code CHARACTER}，任何地方都不得把字符数伪称为 Token。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 预算单位（固定字符，不允许改为 Token）。 */
    private String budgetUnit = "CHARACTER";

    /**
     * 进入 Prompt 的证据上下文最大字符数（UTF-16 code unit）。
     * 已为系统指令、问题与输出预留空间，默认 6000。
     */
    private int contextBudgetChars = 6000;

    /** 为模型输出预留的字符预算（用于预算说明，不参与硬截断）。 */
    private int reservedOutputChars = 1000;

    /** 默认 Top-K，未显式传入时使用；与检索服务默认值保持一致。 */
    private int defaultTopK = 5;

    /**
     * 引用片段（quotedSnippet）最大 UTF-16 字符数，默认 200。
     * 片段由真实命中内容确定性截取，模型不参与生成。
     */
    private int quotedSnippetMaxChars = 200;
}
