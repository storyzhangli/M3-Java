package com.zl.d36.rag.citation;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 引用 URL 安全策略（无状态静态工具）。
 *
 * <p>引用契约要求：只有真实检索元数据中存在、且通过协议与目标校验的 URL 才允许返回。
 * 本类只做确定性校验，不发起任何网络请求，不依赖模型输出。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>仅允许 {@code http} / {@code https} 协议；</li>
 *   <li>拒绝 {@code javascript:}、{@code data:}、{@code file:}、{@code ftp:} 等非 HTTP(S) 协议；</li>
 *   <li>拒绝本机绝对路径、无主机链接、localhost 与环回/内网地址（当前无授权外链场景）；</li>
 *   <li>不安全或无法解析时返回 {@code null}，由调用方隐藏该字段，绝不补造。</li>
 * </ul>
 */
public final class UrlSafetyPolicy {

    /** 明确允许的协议白名单。 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 明确拒绝的危险/非业务协议（用于说明与测试，实际以协议白名单为准）。 */
    private static final Set<String> BLOCKED_SCHEMES = Set.of("javascript", "data", "file", "ftp");

    private UrlSafetyPolicy() {
    }

    /**
     * 判断 URL 是否安全可返回。
     *
     * @param raw 待校验 URL，可为 null
     * @return true 表示通过校验
     */
    public static boolean isSafeUrl(String raw) {
        return sanitize(raw) != null;
    }

    /**
     * 校验并返回安全 URL；不安全或无法解析时返回 null。
     *
     * @param raw 待校验 URL
     * @return 规范化后的 URL，或 null
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim();

        // 本机绝对路径（Windows 盘符，如 C:\...）直接拒绝
        if (isWindowsAbsolutePath(candidate)) {
            return null;
        }

        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return null;
        }
        if (isLocalOrPrivateHost(host)) {
            return null;
        }
        return candidate;
    }

    /**
     * 区分“危险协议/本机路径”与“普通无效”。
     *
     * @param raw 待判定 URL
     * @return true 表示命中明确黑名单（危险协议或本机路径）
     */
    public static boolean isDangerous(String raw) {
        if (raw == null) {
            return false;
        }
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        if (isWindowsAbsolutePath(raw.trim())) {
            return true;
        }
        for (String blocked : BLOCKED_SCHEMES) {
            if (candidate.startsWith(blocked + ":")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWindowsAbsolutePath(String value) {
        if (value.length() < 3) {
            return false;
        }
        char drive = value.charAt(0);
        return Character.isLetter(drive) && value.charAt(1) == ':'
                && (value.charAt(2) == '\\' || value.charAt(2) == '/');
    }

    private static boolean isLocalOrPrivateHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost")) {
            return true;
        }
        // 字面量环回/内网网段先做确定性拦截
        if (isLiteralLoopbackOrPrivate(normalized)) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isSiteLocalAddress() || address.isLinkLocalAddress();
        } catch (Exception ex) {
            // 无法解析主机名时不做放行，按不安全处理
            return true;
        }
    }

    private static boolean isLiteralLoopbackOrPrivate(String host) {
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("169.254.")) {
            return true;
        }
        if (host.startsWith("192.168.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignore) {
                    return false;
                }
            }
        }
        return host.startsWith("[::1]") || host.startsWith("[fc") || host.startsWith("[fd");
    }
}
