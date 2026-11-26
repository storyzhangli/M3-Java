package com.zl.d43.rag.rerank;

import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.zl.d43.resilience.ModelErrorCategory;
import com.zl.d43.resilience.ModelExceptionClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * rerank 异常分类器单测（D5/C01）：永久/瞬时裁决边界 + cause 链穿透 + 未知保守永久。
 * ApiException 用真实对象（Status.builder 构造，javap 实证 2.23.6 builder 面），
 * 仅 SDK 检查异常用 Mockito（构造面不可靠）。
 */
class RerankExceptionClassifierTest {

    private final ModelExceptionClassifier fallback = mock(ModelExceptionClassifier.class);
    private final RerankExceptionClassifier classifier = new RerankExceptionClassifier(fallback);

    private static ApiException apiException(int status, String code) {
        return new ApiException(Status.builder()
                .statusCode(status).code(code).message("controlled-test").build());
    }

    @Test
    @DisplayName("SDK 确定类型：NoApiKey / InputRequired → PERMANENT（E1，不重试）")
    void sdkPermanentTypes() {
        assertEquals(RerankExceptionClassifier.Verdict.PERMANENT,
                classifier.classify(mock(NoApiKeyException.class)));
        assertEquals(RerankExceptionClassifier.Verdict.PERMANENT,
                classifier.classify(mock(InputRequiredException.class)));
    }

    @Test
    @DisplayName("状态码裁决：429/5xx/408 → TRANSIENT；其余 4xx → PERMANENT")
    void statusVerdicts() {
        assertEquals(RerankExceptionClassifier.Verdict.TRANSIENT,
                classifier.classify(apiException(429, "Throttling.RateQuota")));
        assertEquals(RerankExceptionClassifier.Verdict.TRANSIENT,
                classifier.classify(apiException(503, "ServiceUnavailable")));
        assertEquals(RerankExceptionClassifier.Verdict.TRANSIENT,
                classifier.classify(apiException(408, "RequestTimeout")));
        assertEquals(RerankExceptionClassifier.Verdict.PERMANENT,
                classifier.classify(apiException(400, "InvalidParameter")));
        assertEquals(RerankExceptionClassifier.Verdict.PERMANENT,
                classifier.classify(apiException(404, "ModelNotFound")));
        assertEquals(RerankExceptionClassifier.Verdict.PERMANENT,
                classifier.classify(apiException(403, "Arrearage")));
    }

    @Test
    @DisplayName("cause 链穿透：外层 RuntimeException 包 NoApiKey → PERMANENT")
    void causeChainPenetrates() {
        RuntimeException wrapper = new RuntimeException("wrapped",
                mock(NoApiKeyException.class));
        assertEquals(RerankExceptionClassifier.Verdict.PERMANENT,
                classifier.classify(wrapper));
    }

    @Test
    @DisplayName("resilience 兜底：分类器给网络类=瞬时；未知类型=保守永久")
    void fallbackByClassifier() {
        RuntimeException net = new RuntimeException("socket reset");
        when(fallback.classify(net)).thenReturn(ModelErrorCategory.CONNECT_FAILURE);
        assertEquals(RerankExceptionClassifier.Verdict.TRANSIENT, classifier.classify(net));

        RuntimeException unknown = new IllegalStateException("weird");
        when(fallback.classify(unknown)).thenReturn(ModelErrorCategory.UNKNOWN);
        assertEquals(RerankExceptionClassifier.Verdict.PERMANENT, classifier.classify(unknown));
    }
}
