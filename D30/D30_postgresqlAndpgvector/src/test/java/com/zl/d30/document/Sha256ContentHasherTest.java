package com.zl.d30.document;

import com.zl.d30.document.support.Sha256ContentHasher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容哈希计算器测试。
 */
class Sha256ContentHasherTest {

    private final Sha256ContentHasher hasher = new Sha256ContentHasher();

    @Test
    void sameContentSameHash() {
        byte[] a = "repeat".getBytes(StandardCharsets.UTF_8);
        byte[] b = "repeat".getBytes(StandardCharsets.UTF_8);
        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    void differentContentDifferentHash() {
        assertThat(hasher.hash("one".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(hasher.hash("two".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void hashIs64HexChars() {
        String hash = hasher.hash("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void knownEmptyStringSha256Vector() {
        // 空字符串 SHA-256 标准向量
        assertThat(hasher.hash("".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
