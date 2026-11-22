package com.zl.d39.rag.citation;

/**
 * 引用片段确定性截取器（无状态静态工具）。
 *
 * <p>引用片段只能来自本轮真实检索命中的内容，由本类按可配置上限截取，
 * 模型不参与生成。口径为 UTF-16 code unit（与工程预算口径一致，不伪称为 Token）。</p>
 *
 * <p>截取规则：</p>
 * <ul>
 *   <li>空白或 null 内容返回 null（不补造空串）；</li>
 *   <li>内容不超过上限时原样返回；</li>
 *   <li>超限时截断到上限，并保证不切断 UTF-16 代理对（surrogate pair），末尾追加省略号；</li>
 *   <li>返回长度不超过上限。</li>
 * </ul>
 */
public final class SnippetExtractor {

    /** 省略号（单个 UTF-16 字符），用于标识截断。 */
    private static final String ELLIPSIS = "…";

    private SnippetExtractor() {
    }

    /**
     * 截取引用片段。
     *
     * @param content 真实命中内容
     * @param maxChars 最大 UTF-16 字符数（含省略号），必须为正
     * @return 截取后的片段；内容为空白时返回 null
     */
    public static String extract(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return null;
        }
        if (maxChars <= 0) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }

        // 预留省略号位置
        int cut = maxChars - 1;
        if (cut <= 0) {
            return ELLIPSIS;
        }
        // 若切点落在代理对的高代理项上，回退一个字符，避免产生孤立代理
        if (Character.isHighSurrogate(trimmed.charAt(cut - 1))
                && Character.isLowSurrogate(trimmed.charAt(cut))) {
            cut--;
            if (cut <= 0) {
                return ELLIPSIS;
            }
        }
        return trimmed.substring(0, cut) + ELLIPSIS;
    }
}
