package com.zl.d43.access;

import com.zl.d43.access.AuthContextFilter;
import com.zl.d43.access.AuthRole;

import java.util.Set;

/**
 * 请求级授权上下文（不可变 record，禁 Map，用户硬契约）。
 *
 * <p>由 {@link AuthContextFilter} 在服务端从 Bearer 凭据解析并注入 request attribute；
 * 全链路只读取本对象，绝不读取客户端声明的身份字段（H3）。</p>
 *
 * <p>SSE/异步注意：本对象不可变，可在请求线程内捕获快照后显式传递给异步闭包；
 * 禁止依赖 ThreadLocal 跨线程读取（§3.3，单测覆盖）。</p>
 *
 * @param userId       业务用户标识（同时作为会话/历史 owner，D6）
 * @param tenantId     租户（硬隔离边界，H7）
 * @param deptId       部门
 * @param deviceScopes 可见设备集合（服务端解析，不可变；可为空集=无专属设备，仍可看 '*' 文档）
 * @param role         服务端角色
 */
public record AuthContext(String userId,
                          String tenantId,
                          String deptId,
                          Set<String> deviceScopes,
                          AuthRole role) {

    /** 请求属性键：AuthContext 注入位（唯一通道，避免裸字符串散落）。 */
    public static final String REQUEST_ATTRIBUTE = "com.zl.d43.access.AuthContext";

    public AuthContext {
        deviceScopes = Set.copyOf(deviceScopes);
    }

    public boolean admin() {
        return role == AuthRole.ADMIN;
    }
}
