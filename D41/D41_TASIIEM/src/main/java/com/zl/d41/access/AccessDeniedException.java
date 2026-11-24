package com.zl.d41.access;

/**
 * 授权拒绝异常（fail-closed，H5）。
 *
 * <p>内部保留细分原因码（供服务端日志与测试断言），对外由
 * {@link AccessDeniedAdvice} 统一映射为稳定业务码（AccessProperties.denyCode），
 * 不泄露缺失/无效/过期等差异（防凭据枚举探测）。</p>
 */
public class AccessDeniedException extends RuntimeException {

    /** 稳定原因码（内部诊断用，不进对外响应体）。 */
    public enum Reason {
        /** 无 Authorization 头或非 Bearer 方案 */
        CREDENTIAL_MISSING,
        /** 哈希查库无此凭据 */
        CREDENTIAL_INVALID,
        /** 凭据过期 */
        CREDENTIAL_EXPIRED,
        /** 凭据被禁用 */
        CREDENTIAL_DISABLED,
        /** 凭据存储不可达（MySQL 故障，绝不降级为匿名放行，H5） */
        STORE_UNREACHABLE,
        /** 服务层执行时无请求级 AuthContext（SSE/异步线程未显式传递快照） */
        CONTEXT_MISSING,
        /** 目标资源不在授权范围内（文档级校验） */
        OUT_OF_SCOPE,
        /** 需要 ADMIN 角色 */
        ADMIN_REQUIRED,
        /** 权限体系内部不变式破坏（谓词构建等），fail-closed */
        POLICY_BROKEN
    }

    private final Reason reason;

    public AccessDeniedException(Reason reason) {
        super("ACCESS_DENIED:" + reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
