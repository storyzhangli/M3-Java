package com.zl.d28.document.support;

import com.zl.d28.document.config.DocumentImportProperties;
import com.zl.d28.document.exception.DocumentValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;

/**
 * 服务端文件校验器。
 *
 * <p>不依赖前端、不仅看扩展名：依次校验读取失败、空文件、大小上限、
 * 扩展名与内容类型白名单、字符集严格解码。任一失败抛 {@link DocumentValidationException}。</p>
 */
@Component
public class FileUploadValidator {

    private static final String GENERIC_BINARY = "application/octet-stream";

    private final DocumentImportProperties properties;

    public FileUploadValidator(DocumentImportProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取并校验上传文件，返回原始字节。
     */
    public byte[] validate(MultipartFile file) {
        if (file == null) {
            throw new DocumentValidationException("上传文件缺失");
        }
        byte[] bytes = readBytes(file);
        validateNotEmpty(bytes, file);
        validateSize(bytes);
        String extension = validateType(file);
        // PDF 为二进制，不能走仅用于文本的 UTF-8 严格解码；文本类型才做编码校验。
        if (!"pdf".equals(extension)) {
            validateEncoding(bytes);
        }
        return bytes;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception e) {
            throw new DocumentValidationException("文件读取失败，请重新上传");
        }
    }

    private void validateNotEmpty(byte[] bytes, MultipartFile file) {
        if (file.isEmpty() || bytes.length == 0) {
            throw new DocumentValidationException("文件为空，禁止导入空文档");
        }
    }

    private void validateSize(byte[] bytes) {
        if (bytes.length > properties.getMaxFileSizeBytes()) {
            throw new DocumentValidationException(
                    "文件大小超过服务端上限（" + properties.getMaxFileSizeBytes() + " 字节）");
        }
    }

    private String validateType(MultipartFile file) {
        String filename = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        if (!StringUtils.hasText(filename) || filename.endsWith(".")) {
            throw new DocumentValidationException("文件名缺失或非法");
        }
        String extension = extractExtension(filename);
        if (!properties.getAllowedExtensions().contains(extension)) {
            throw new DocumentValidationException("不支持的文件类型，仅允许: " + properties.getAllowedExtensions());
        }
        String contentType = file.getContentType();
        if (StringUtils.hasText(contentType)
                && !GENERIC_BINARY.equalsIgnoreCase(contentType)
                && !properties.getAllowedContentTypes().contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new DocumentValidationException("文件内容类型不在允许范围: " + contentType);
        }
        return extension;
    }

    private String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void validateEncoding(byte[] bytes) {
        Charset charset = Charset.forName(properties.getCharset());
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            if (!decoded.toString().chars().anyMatch(Character::isLetterOrDigit)) {
                // 非零字节但解码后没有任何可用文字：属于解析阶段不可用，编码本身合法，这里放行
                return;
            }
        } catch (CharacterCodingException e) {
            throw new DocumentValidationException("文件编码非法，要求 " + properties.getCharset() + " 编码");
        } catch (IllegalStateException e) {
            throw new DocumentValidationException("文件编码非法，要求 " + properties.getCharset() + " 编码");
        }
    }
}
