package com.zl.d41.access;

import com.zl.d41.access.AccessDeniedException;
import com.zl.d41.access.AccessProperties;
import com.zl.d41.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 授权拒绝专用 advice（最高优先，仅接管 {@link AccessDeniedException}）。
 *
 * <p>与既有 GlobalExceptionHandler 零冲突：其余异常继续由其处理；
 * 对外同样遵循 HTTP 200 + Result 包装契约（教训 §3.7）。
 * 响应只回稳定文案，内部原因码仅写服务端日志（H5/H9）。</p>
 */
@Slf4j
@Order(-100)
@RestControllerAdvice
public class AccessDeniedAdvice {

    private final AccessProperties properties;

    public AccessDeniedAdvice(AccessProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException ex) {
        log.warn("授权拒绝: reason={}", ex.reason().name());
        return Result.fail(properties.getDenyCode(), properties.getDenyMessage());
    }
}
