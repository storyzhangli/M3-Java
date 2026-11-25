package com.zl.d42.access;

import com.zl.d42.access.AuthRole;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 服务端凭据表行（d42_auth_principal，MySQL 权威存储）。
 *
 * <p>只存 token 的 SHA-256 十六进制哈希（主键），明文任何层不落库不落日志（H9）。</p>
 *
 * @param tokenSha256  令牌哈希（CHAR(64) 主键）
 * @param userId       业务用户
 * @param tenantId     租户
 * @param deptId       部门
 * @param deviceScopes 设备范围（JSON 数组解析后的显式集合）
 * @param role         服务端角色
 * @param enabled      启用位（禁用=fail-closed 拒绝）
 * @param expiresAt    过期时刻（NULL=不过期；非空且早于当前=拒绝）
 */
public record AuthPrincipal(String tokenSha256,
                            String userId,
                            String tenantId,
                            String deptId,
                            Set<String> deviceScopes,
                            AuthRole role,
                            boolean enabled,
                            LocalDateTime expiresAt) {
}
