package com.zl.d35.rag.citation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UrlSafetyPolicy} 单元测试：协议白名单、危险协议、本机路径与内网地址拦截。
 */
class UrlSafetyPolicyTest {

    @Test
    @DisplayName("http/https 公网链接通过校验（本机已确认可解析）")
    void safePublicHttpUrls() {
        assertTrue(UrlSafetyPolicy.isSafeUrl("https://example.com/manual"));
        assertTrue(UrlSafetyPolicy.isSafeUrl("http://example.com/path?x=1"));
    }

    @Test
    @DisplayName("无法解析的主机名 fail-closed，不做放行")
    void unresolvableHostFailClosed() {
        // .invalid 为 RFC 2606 保留 TLD，保证不会解析
        String url = "https://nonexistent-d35-host.invalid/x";
        assertFalse(UrlSafetyPolicy.isSafeUrl(url));
        assertNull(UrlSafetyPolicy.sanitize(url));
    }

    @Test
    @DisplayName("危险协议 javascript/data/file/ftp 被拦截且标记为危险")
    void blockedDangerousSchemes() {
        String[] dangerous = {
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "file:///etc/passwd",
                "ftp://files.example.com/a.txt"
        };
        for (String url : dangerous) {
            assertFalse(UrlSafetyPolicy.isSafeUrl(url), url);
            assertTrue(UrlSafetyPolicy.isDangerous(url), url);
            assertNull(UrlSafetyPolicy.sanitize(url));
        }
    }

    @Test
    @DisplayName("localhost 与环回/内网 IP 被拦截")
    void blockedLocalAndPrivateHosts() {
        String[] hosts = {
                "http://localhost:8080/x",
                "https://127.0.0.1/x",
                "http://10.0.0.5/x",
                "http://192.168.1.10/x",
                "http://172.16.4.20/x",
                "http://169.254.1.1/x"
        };
        for (String url : hosts) {
            assertFalse(UrlSafetyPolicy.isSafeUrl(url), url);
        }
    }

    @Test
    @DisplayName("本机绝对路径与空白输入被拦截")
    void blockedLocalPathsAndBlank() {
        assertFalse(UrlSafetyPolicy.isSafeUrl("C:\\Users\\zl\\secret.txt"));
        assertTrue(UrlSafetyPolicy.isDangerous("C:\\Users\\zl\\secret.txt"));
        assertFalse(UrlSafetyPolicy.isSafeUrl("   "));
        assertFalse(UrlSafetyPolicy.isSafeUrl(null));
    }

    @Test
    @DisplayName("无主机或无法解析的链接不通过")
    void blockedMissingHost() {
        assertFalse(UrlSafetyPolicy.isSafeUrl("https://"));
        assertFalse(UrlSafetyPolicy.isSafeUrl("not-a-url"));
    }
}
