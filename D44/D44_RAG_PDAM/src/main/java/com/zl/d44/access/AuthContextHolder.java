package com.zl.d44.access;

import com.zl.d44.access.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求级 AuthContext 读取入口（唯一合法通道）。
 *
 * <p>实现说明：基于 Servlet 请求属性（非自定义 ThreadLocal）；同步链路内服务层可安全读取。
 * SSE/异步线程禁止依赖本入口隐式取上下文——必须在请求线程内调用 {@link #current()}
 * 捕获不可变 {@link AuthContext} 快照后显式传入异步闭包（§3.3，单测覆盖）。</p>
 */
public final class AuthContextHolder {

    private AuthContextHolder() {
    }

    /** 当前请求的 AuthContext；缺失（未过过滤器/非请求线程）抛 fail-closed。 */
    public static AuthContext current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            Object ctx = request.getAttribute(AuthContext.REQUEST_ATTRIBUTE);
            if (ctx instanceof AuthContext authContext) {
                return authContext;
            }
        }
        throw new AccessDeniedException(AccessDeniedException.Reason.CONTEXT_MISSING);
    }

    /** 当前请求的 AuthContext；缺失返回 null（供 enabled 门禁分支判断使用）。 */
    public static AuthContext currentOrNull() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            if (servletAttributes.getRequest()
                    .getAttribute(AuthContext.REQUEST_ATTRIBUTE) instanceof AuthContext authContext) {
                return authContext;
            }
        }
        return null;
    }
}
