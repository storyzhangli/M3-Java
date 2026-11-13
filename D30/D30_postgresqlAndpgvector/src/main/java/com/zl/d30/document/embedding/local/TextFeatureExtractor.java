package com.zl.d30.document.embedding.local;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本特征提取器：为词面（lexical）向量构造特征集合。
 *
 * <p>三类特征：</p>
 * <ul>
 *   <li>词特征 {@code w:}：连续字母/数字序列（小写），适配英文与数字；</li>
 *   <li>字符特征 {@code c:}：每个非空白字符（小写），适配中文等无空格语言；</li>
 *   <li>字符二元特征 {@code g:}：相邻非空白字符的 bigram，捕捉局部顺序。</li>
 * </ul>
 *
 * <p>该提取器只刻画字面共现，不具备神经语义理解能力。</p>
 */
public final class TextFeatureExtractor {

    private TextFeatureExtractor() {
    }

    public static List<String> extract(String text) {
        List<String> features = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return features;
        }
        String lower = text.toLowerCase();

        // 词特征：连续字母/数字
        int i = 0;
        while (i < lower.length()) {
            char ch = lower.charAt(i);
            if (isTokenChar(ch)) {
                int start = i;
                while (i < lower.length() && isTokenChar(lower.charAt(i))) {
                    i++;
                }
                features.add("w:" + lower.substring(start, i));
            } else {
                i++;
            }
        }

        // 字符特征与 bigram：忽略空白
        char prev = 0;
        for (int j = 0; j < lower.length(); j++) {
            char ch = lower.charAt(j);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            features.add("c:" + ch);
            if (prev != 0) {
                features.add("g:" + prev + ch);
            }
            prev = ch;
        }
        return features;
    }

    private static boolean isTokenChar(char ch) {
        return Character.isLetterOrDigit(ch);
    }
}
