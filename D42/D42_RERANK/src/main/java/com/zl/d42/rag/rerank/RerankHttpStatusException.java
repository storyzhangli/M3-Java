package com.zl.d42.rag.rerank;

/**
 * DashScope 原生 HTTP rerank 端点的非 2xx 响应异常（D-EX8：SDK 2.23.6 TextReRank
 * 构造路径 getProtocol()=null 缺陷，A2 通道改走 JDK HttpClient 直连原生端点）。
 *
 * <p>携带上游 HTTP 状态码与错误码（如 InvalidApiKey / Throttling），供
 * {@link RerankExceptionClassifier} 做永久/瞬时裁决；message 只含状态与错误码，
 * 绝不含 Key 或文档内容。</p>
 */
public class RerankHttpStatusException extends RuntimeException {

    private final int statusCode;
    private final String upstreamCode;

    public RerankHttpStatusException(int statusCode, String upstreamCode) {
        super("rerank upstream http status=" + statusCode + ", code=" + upstreamCode);
        this.statusCode = statusCode;
        this.upstreamCode = upstreamCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getUpstreamCode() {
        return upstreamCode;
    }
}
