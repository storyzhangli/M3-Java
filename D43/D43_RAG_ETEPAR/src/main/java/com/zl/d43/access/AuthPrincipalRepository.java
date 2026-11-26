package com.zl.d43.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d43.access.AuthPrincipal;
import com.zl.d43.access.AuthRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 凭据表只读仓储（MySQL ai_db.d43_auth_principal，服务端权威身份源，H3）。
 *
 * <p>仅 SELECT；存储不可达（DataAccessException）不吞异常、不降级——由过滤器
 * 映射为 STORE_UNREACHABLE 并 fail-closed（H5）。</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "app.access", name = "enabled", havingValue = "true")
public class AuthPrincipalRepository {

    private static final String SELECT_BY_HASH =
            "SELECT token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled, expires_at "
                    + "FROM d43_auth_principal WHERE token_sha256 = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuthPrincipalRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
                                   ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 按 token SHA-256 查凭据；不存在返回 empty（缺失≠失败，区分由过滤器处理）。 */
    public Optional<AuthPrincipal> findByTokenHash(String tokenSha256) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_BY_HASH, tokenSha256);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new AuthPrincipal(
                (String) row.get("token_sha256"),
                (String) row.get("user_id"),
                (String) row.get("tenant_id"),
                (String) row.get("dept_id"),
                parseDeviceScopes((String) row.get("device_scopes")),
                AuthRole.fromStorage((String) row.get("role")),
                isTruthy(row.get("enabled")),
                (LocalDateTime) row.get("expires_at")));
    }

    /**
     * device_scopes JSON 数组 → 有序去重集合；NULL/空串/非法 JSON → 空集。
     * 空集=该 principal 无专属设备，仅可见 deviceId='*' 文档（fail-closed，不用 null 表达不限）。
     */
    Set<String> parseDeviceScopes(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            Set<String> scopes = new LinkedHashSet<>();
            for (String item : list) {
                if (item != null && !item.isBlank()) {
                    scopes.add(item.trim());
                }
            }
            return scopes;
        } catch (Exception ex) {
            // 不回显报文内容（可能含敏感结构），仅记录类型
            log.warn("device_scopes 解析失败，按空集 fail-closed 处理: {}", ex.getClass().getSimpleName());
            return Set.of();
        }
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof Number n && n.intValue() != 0;
    }
}
