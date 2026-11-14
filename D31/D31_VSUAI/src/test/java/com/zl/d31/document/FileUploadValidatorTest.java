package com.zl.d31.document;

import com.zl.d31.document.config.DocumentImportProperties;
import com.zl.d31.document.exception.DocumentValidationException;
import com.zl.d31.document.support.FileUploadValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件校验器测试：使用真实字节与真实 MockMultipartFile，不以 Mock 成功代替真实校验。
 */
class FileUploadValidatorTest {

    private DocumentImportProperties properties;
    private FileUploadValidator validator;

    @BeforeEach
    void setUp() {
        properties = new DocumentImportProperties();
        properties.setMaxFileSizeBytes(10);
        validator = new FileUploadValidator(properties);
    }

    @Test
    void validFilePasses() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        byte[] bytes = validator.validate(file);
        assertThat(bytes).hasSize(5);
    }

    @Test
    void emptyFileRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("空");
    }

    @Test
    void oversizedFileRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.txt", "text/plain", "12345678901".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("上限");
    }

    @Test
    void illegalEncodingRejected() {
        // 0xFF 0xFE 不是合法 UTF-8 起始序列，严格解码应拒绝
        byte[] invalid = {(byte) 0xFF, (byte) 0xFE, 0x65};
        MockMultipartFile file = new MockMultipartFile(
                "file", "bad.txt", "text/plain", invalid);
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("编码");
    }

    @Test
    void unsupportedExtensionRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", "abc".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    void unsupportedContentTypeRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "image/png", "abc".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("内容类型");
    }

    @Test
    void readFailureRejected() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getBytes()).thenThrow(new IOException("disk error"));
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("读取失败");
    }

    @Test
    void genericOctetStreamWithAllowedExtensionAccepted() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.md", "application/octet-stream", "# title".getBytes(StandardCharsets.UTF_8));
        assertThat(validator.validate(file)).isNotEmpty();
    }

    @Test
    void pdfBinaryAcceptedWithoutUtf8Decoding() {
        // 含非 UTF-8 字节的 PDF 二进制：应通过类型校验且跳过文本编码检查
        byte[] pdfBytes = {(byte) 0x25, 0x50, 0x44, 0x46, (byte) 0xFF, (byte) 0xD8};
        MockMultipartFile file = new MockMultipartFile(
                "file", "manual.pdf", "application/pdf", pdfBytes);
        assertThat(validator.validate(file)).hasSize(6);
    }
}
