package com.zl.d41.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d41.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 服务端认证过滤器（仅 {@code app.access.enabled=true} 装配，H2）。
 *
 * <p>职责链：Bearer 头提取 → SHA-256 → 凭据表查询 → enabled/expires 校验 →
 * 注入请求属性 {@link AuthContext#REQUEST_ATTRIBUTE}。任何失败一律 fail-closed：
 * HTTP 200 + Result.code=denyCode（继承项目 Result 包装契约），统一文案不泄差异（防枚举）。</p>
 *
 * <p>身份唯一来源=服务端凭据表；请求体/参数/自定义头中的 userId/role/tenantId
 * 声明在本过滤器之后无任何读取通道（H3/T2）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.access", name = "enabled", havingValue = "true")
public class AuthContextFilter extends OncePerRequestFilter {

    private final AccessProperties properties;
    private final AuthPrincipalRepository principalRepository;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthContextFilter(AccessProperties properties,
                             AuthPrincipalRepository principalRepository,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.principalRepository = principalRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isPublic(request)) {
            chain.doFilter(request, response);
            return;
        }
        AuthDenied denied = null;
        AuthContext context = null;
        try {
            context = authenticate(request);
        } catch (AuthDenied e) {
            denied = e;
        }
        if (denied != null) {
            // 日志只记原因码与路径，绝不记 token（H9）
            log.warn("认证拒绝: reason={}, path={}", denied.reason, request.getRequestURI());
            writeDeny(response);
            return;
        }
        request.setAttribute(AuthContext.REQUEST_ATTRIBUTE, context);
        chain.doFilter(request, response);
    }

    private boolean isPublic(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String pattern : properties.getPublicPaths()) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    private AuthContext authenticate(HttpServletRequest request) throws AuthDenied {
        String header = request.getHeader(properties.getTokenHeader());
        if (header == null || !header.startsWith(properties.getBearerPrefix())) {
            throw new AuthDenied(AccessDeniedException.Reason.CREDENTIAL_MISSING);
        }
        String token = header.substring(properties.getBearerPrefix().length()).trim();
        if (token.isEmpty()) {
            throw new AuthDenied(AccessDeniedException.Reason.CREDENTIAL_MISSING);
        }
        Optional<AuthPrincipal> principal;
        try {
            principal = principalRepository.findByTokenHash(AccessTokenHasher.sha256Hex(token));
        } catch (RuntimeException ex) {
            // 凭据存储不可达：fail-closed 拒绝，绝不匿名放行（H5）；不回显 SQL/驱动细节
            log.error("凭据存储不可达，按 fail-closed 拒绝: {}", ex.getClass().getSimpleName());
            throw new AuthDenied(AccessDeniedException.Reason.STORE_UNREACHABLE);
        }
        AuthPrincipal row = principal.orElseThrow(
                () -> new AuthDenied(AccessDeniedException.Reason.CREDENTIAL_INVALID));
        if (!row.enabled()) {
            throw new AuthDenied(AccessDeniedException.Reason.CREDENTIAL_DISABLED);
        }
        if (row.expiresAt() != null && row.expiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthDenied(AccessDeniedException.Reason.CREDENTIAL_EXPIRED);
        }
        return new AuthContext(row.userId(), row.tenantId(), row.deptId(),
                row.deviceScopes(), row.role());
    }

    private void writeDeny(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(properties.getDenyCode(), properties.getDenyMessage())));
    }

    /** 过滤器内部拒绝信号（携带内部原因码，对外统一折叠）。 */
    private static final class AuthDenied extends Exception {
        private final AccessDeniedException.Reason reason;

        private AuthDenied(AccessDeniedException.Reason reason) {
            this.reason = reason;
        }
    }
}
