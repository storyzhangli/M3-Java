package com.zl.d42.rag.rerank;

import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.zl.d42.resilience.ModelErrorCategory;
import com.zl.d42.resilience.ModelExceptionClassifier;

/**
 * rerank 专用异常分类器（D5/P1.6）：把 DashScope rerank 调用面的任意 Throwable
 * 归一为 {@link Verdict}（永久不重试 / 瞬时应用层单次重试），复用 resilience 既有
 * {@link ModelExceptionClassifier} 做网络/超时兜底，对齐"M3 韧性约定：内置重试关闭、
 * 应用层单一重试层、永久错误不重试"。
 */
public class RerankExceptionClassifier {

    /** 裁决：永久错误（不重试）或瞬时错误（允许恰好一次重试）。 */
    public enum Verdict {
        /** 鉴权/参数/模型不存在/内容策略/请求非法——重试无意义。 */
        PERMANENT,
        /** 超时/连接失败/限流/5xx——瞬时，单次重试。 */
        TRANSIENT
    }

    private final ModelExceptionClassifier fallback;

    public RerankExceptionClassifier(ModelExceptionClassifier fallback) {
        this.fallback = fallback;
    }

    /**
     * 归一分类。判定优先级：确定的 SDK 异常类型 → ApiException 状态码/错误码
     * → resilience 通用分类器（cause 链网络信号）。
     *
     * @param throwable 调用抛出的异常
     * @return 永久/瞬时裁决；未知异常按永久处理（保守：避免对未知错误重试放大）
     */
    public Verdict classify(Throwable throwable) {
        if (throwable == null) {
            return Verdict.PERMANENT;
        }
        // D-EX8 HTTP 通道：原生端点非 2xx 专用异常，语义对齐 verdictFromApiException
        if (throwable instanceof RerankHttpStatusException httpEx) {
            int status = httpEx.getStatusCode();
            String code = httpEx.getUpstreamCode() == null ? "" : httpEx.getUpstreamCode();
            if (status == 429 || status == 408 || status >= 500
                    || code.contains("Throttling") || code.contains("FlowControl")) {
                return Verdict.TRANSIENT;
            }
            return Verdict.PERMANENT;
        }
        // D-EX8 HTTP 通道：JDK HttpClient 超时（extends IOException，但 resilience
        // 兜底按类型名判定不保证覆盖 HttpTimeoutException，此处显式归瞬时，保住 E2 语义）
        if (throwable instanceof java.net.http.HttpTimeoutException) {
            return Verdict.TRANSIENT;
        }
        // SDK 确定类型：缺 Key、输入非法 = 永久（E1）
        if (throwable instanceof NoApiKeyException || throwable instanceof InputRequiredException) {
            return Verdict.PERMANENT;
        }
        if (throwable instanceof ApiException apiException) {
            Verdict byStatus = verdictFromApiException(apiException);
            if (byStatus != null) {
                return byStatus;
            }
        }
        // cause 链上可能包着 SDK 异常
        for (Throwable node = throwable.getCause(); node != null; node = node.getCause()) {
            if (node instanceof NoApiKeyException || node instanceof InputRequiredException) {
                return Verdict.PERMANENT;
            }
            if (node instanceof ApiException nested) {
                Verdict byStatus = verdictFromApiException(nested);
                if (byStatus != null) {
                    return byStatus;
                }
            }
        }
        // resilience 兜底：连接/读超时/瞬断 → 瞬时；其余 → 永久
        ModelErrorCategory category = fallback.classify(throwable);
        return switch (category) {
            case CONNECT_FAILURE, READ_TIMEOUT, RATE_LIMIT_RECOVERABLE, UPSTREAM_UNAVAILABLE ->
                    Verdict.TRANSIENT;
            default -> Verdict.PERMANENT;
        };
    }

    /**
     * ApiException 状态码/错误码裁决。
     * 429/Throttling=瞬时；408/400 超时瞬时；5xx=瞬时；其余 4xx 与无状态=永久。
     */
    private Verdict verdictFromApiException(ApiException e) {
        if (e.getStatus() != null) {
            int status = e.getStatus().getStatusCode();
            String code = e.getStatus().getCode() == null ? "" : e.getStatus().getCode();
            if (status == 429 || code.contains("Throttling") || code.contains("FlowControl")) {
                return Verdict.TRANSIENT;
            }
            if (status == 408 || status >= 500) {
                return Verdict.TRANSIENT;
            }
            if (status >= 400 && status < 500) {
                return Verdict.PERMANENT;
            }
            // 余额/配额类错误码按永久（E5 由预算帽另行兜底）
            if (code.contains("Arrearage") || code.contains("QuotaExceeded")) {
                return Verdict.PERMANENT;
            }
            return null;
        }
        return null;
    }
}
