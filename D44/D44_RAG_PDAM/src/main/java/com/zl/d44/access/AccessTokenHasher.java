package com.zl.d44.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 令牌哈希工具：SHA-256(UTF-8 原文) → 64 位小写 hex。
 * 纯函数，种子脚本与运行时过滤共用同一实现，保证两侧哈希一致（DDL/夹具契约）。
 */
public final class AccessTokenHasher {

    private AccessTokenHasher() {
    }

    public static String sha256Hex(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
