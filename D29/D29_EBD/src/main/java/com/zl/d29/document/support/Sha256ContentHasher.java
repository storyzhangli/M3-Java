package com.zl.d29.document.support;

import com.zl.d29.document.exception.DocumentValidationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容哈希计算器：对原始字节计算 SHA-256 并输出小写十六进制。
 *
 * <p>重复识别基于文件真实内容而非文件名；算法确定性，相同字节恒得相同哈希。</p>
 */
@Component
public class Sha256ContentHasher {

    public String hash(byte[] content) {
        if (content == null) {
            throw new DocumentValidationException("文件内容为空，无法计算内容哈希");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = digest.digest(content);
            StringBuilder hex = new StringBuilder(digestBytes.length * 2);
            for (byte b : digestBytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 内置标准算法，正常环境必然存在；此处为安全兜底，不伪造结果
            throw new DocumentValidationException("当前环境不支持 SHA-256 摘要算法");
        }
    }

    public String hash(String content) {
        return hash(content == null ? null : content.getBytes(StandardCharsets.UTF_8));
    }
}
