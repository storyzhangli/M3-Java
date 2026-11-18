package com.zl.d35.rag.citation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnippetExtractor} 单元测试：截取上限、代理对安全与空白处理。
 */
class SnippetExtractorTest {

    @Test
    @DisplayName("空白内容与非正上限返回 null")
    void blankAndInvalidLimit() {
        assertNull(SnippetExtractor.extract(null, 200));
        assertNull(SnippetExtractor.extract("   ", 200));
        assertNull(SnippetExtractor.extract("内容", 0));
    }

    @Test
    @DisplayName("内容不超过上限时原样返回")
    void shortContentReturnedAsIs() {
        assertEquals("短内容", SnippetExtractor.extract("  短内容  ", 200));
    }

    @Test
    @DisplayName("超长内容截断且长度不超过上限，末尾为省略号")
    void longContentTruncated() {
        String content = "abcdefghijklmnopqrstuvwxyz0123456789";
        String out = SnippetExtractor.extract(content, 10);
        assertEquals(10, out.length());
        assertTrue(out.endsWith("…"));
    }

    @Test
    @DisplayName("切点落在代理对上时回退，不产生孤立代理")
    void surrogatePairNotSplit() {
        String emoji = "😀"; // 单个 emoji 占两个 UTF-16 code unit
        String content = "1234" + emoji + "abcdefghijklmnopqrstuvwxyz";
        String out = SnippetExtractor.extract(content, 6);
        assertEquals("1234…", out);
        for (int i = 0; i < out.length(); i++) {
            char ch = out.charAt(i);
            assertTrue(!Character.isHighSurrogate(ch) && !Character.isLowSurrogate(ch));
        }
    }
}
