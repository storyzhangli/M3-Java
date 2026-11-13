package com.zl.d30.document.support;

import com.zl.d30.document.config.DocumentImportProperties;
import com.zl.d30.document.exception.DocumentParseException;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * 最小文档解析器（D30 基础解析）。
 *
 * <p>仅做字符集解码与可用性判断：要求解码后存在至少一个文字/数字字符，
 * 否则抛 {@link DocumentParseException}。不做 Chunk 切分、不做 Embedding、
 * 不做全量 Tika 接线，保持确定性与可测试。</p>
 */
@Component
public class BasicDocumentParser {

    private final DocumentImportProperties properties;

    public BasicDocumentParser(DocumentImportProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析原始字节，返回解码后的文本。
     */
    public String parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentParseException("解析失败：文档无内容");
        }
        String text = new String(bytes, Charset.forName(properties.getCharset()));
        boolean hasUsable = text.chars().anyMatch(Character::isLetterOrDigit);
        if (!hasUsable) {
            throw new DocumentParseException("解析失败：文档不包含任何可用文字");
        }
        return text;
    }
}
