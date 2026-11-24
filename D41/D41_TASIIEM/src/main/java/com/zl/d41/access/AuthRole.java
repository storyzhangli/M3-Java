package com.zl.d41.access;

/**
 * 服务端角色（来源=凭据表 role 列，唯一权威；请求体/头/参数中的任何角色声明一律忽略，H3/T2）。
 *
 * <p>H7 租户硬边界：ADMIN 仅在本租户内 bypass 部门/设备限制，任何角色不得跨租户。</p>
 */
public enum AuthRole {

    /** 普通用户：租户 AND 部门（含 '*' 通配）AND 设备（含 '*' 通配）。 */
    USER,

    /** 租户管理员：本租户全部已打标文档可见；跨租户零可见。 */
    ADMIN;

    /** 解析凭据表字符串值；未知值 fail-closed 抛错（不默认放行）。 */
    public static AuthRole fromStorage(String raw) {
        if ("USER".equals(raw)) {
            return USER;
        }
        if ("ADMIN".equals(raw)) {
            return ADMIN;
        }
        throw new IllegalStateException("AUTH_ROLE_INVALID: 凭据表 role 值非法");
    }
}
