package com.zl.d29.document;

import com.zl.d29.document.config.DocumentImportProperties;
import com.zl.d29.document.exception.DocumentParseException;
import com.zl.d29.document.support.BasicDocumentParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基础解析器测试。
 */
class BasicDocumentParserTest {

    private final BasicDocumentParser parser =
            new BasicDocumentParser(new DocumentImportProperties());

    @Test
    void parseUsableText() {
        String text = parser.parse("hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(text).contains("hello");
    }

    @Test
    void parseBlankOnlyFails() {
        // 非零字节但仅含空白/控制字符，无可用文字
        byte[] onlyWhitespace = " \n\t\r".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(onlyWhitespace))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("可用文字");
    }

    @Test
    void parseEmptyFails() {
        assertThatThrownBy(() -> parser.parse(new byte[0]))
                .isInstanceOf(DocumentParseException.class);
    }
}
