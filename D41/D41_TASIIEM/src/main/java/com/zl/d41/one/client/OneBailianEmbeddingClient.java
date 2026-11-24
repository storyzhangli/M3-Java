package com.zl.d41.one.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d41.one.config.OneEmbeddingProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * one_ 轨百炼 OpenAI 兼容 Embedding 客户端（JDK HttpClient + Jackson，零新增依赖）。
 *
 * <p>选型理由（任务书 P1 决策）：超时/重试分类/usage 提取/脱敏完全可控可审计；
 * 骨架预置的 dashscope-sdk-java / openai-java 保留不动，本轮不使用（原生通道备选）。</p>
 *
 * <p>工程要点：</p>
 * <ul>
 *   <li>POST {baseUrl}/embeddings，body {model, input, encoding_format=float}；
 *       dimensions 参数仅当 sendDimensionsParam=true 时携带（P0 证实原生 1024，默认不携带）；</li>
 *   <li>重试仅 429/5xx/连接类 IO，最多 maxAttempts 次尝试（默认 2），退避 200ms×2.0 抖动 0.2，
 *       受 totalDeadline 总墙钟约束；400/401/403/404 与读取超时（默认）不重试；</li>
 *   <li>usage.total_tokens 真实累计（LongAdder 线程安全），API 未返回 usage 时单独计数，
 *       禁止用字符数估算冒充 Token；</li>
 *   <li>密钥卫生：Authorization 头不落日志；错误消息剔除密钥本体/Bearer/sk- 模式并截断 ≤500 字符。</li>
 * </ul>
 */
@Slf4j
public class OneBailianEmbeddingClient {

    private final OneEmbeddingProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 真实 HTTP 调用计数（含重试的每次物理请求）。 */
    private final LongAdder callCount = new LongAdder();
    /** API usage.total_tokens 真实累计。 */
    private final LongAdder totalTokens = new LongAdder();
    /** 未返回 usage 的调用数（USAGE_UNAVAILABLE，保守观测口径）。 */
    private final LongAdder usageUnavailableCalls = new LongAdder();

    public OneBailianEmbeddingClient(OneEmbeddingProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 单批向量化（texts.size() 必须 ≤ maxBatch，分批由上层模型负责）。
     *
     * @param texts 输入文本（非空、已做长度校验）
     * @return 与输入顺序一致的向量列表
     */
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new OneEmbeddingException(0, "BAD_REQUEST", false, "one_ embed 输入为空");
        }
        if (texts.size() > properties.getMaxBatch()) {
            throw new OneEmbeddingException(0, "BATCH_TOO_LARGE", false,
                    "one_ embed 批量 " + texts.size() + " 超过上限 " + properties.getMaxBatch());
        }
        String body = buildRequestBody(texts);
        long deadlineNanos = System.nanoTime() + properties.getTotalDeadline().toNanos();
        int maxAttempts = properties.getMaxAttempts();
        OneEmbeddingException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new OneEmbeddingException(0, "DEADLINE_EXCEEDED", false,
                        "one_ embed 总墙钟超限（" + properties.getTotalDeadline().toMillis() + "ms）");
            }
            callCount.increment();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/embeddings"))
                    .timeout(properties.getReadTimeout())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long ms = (System.nanoTime() - start) / 1_000_000;
                int status = response.statusCode();
                if (status == 200) {
                    List<float[]> vectors = parseResponse(response.body(), texts.size());
                    log.info("one_ embed 成功: batch={}, dims={}, ms={}, attempt={}", texts.size(), properties.getDimensions(), ms, attempt);
                    return vectors;
                }
                String sanitized = sanitize(response.body());
                boolean retryable = status == 429 || status >= 500;
                String code = classifyErrorCode(status);
                log.warn("one_ embed 失败: status={}, code={}, retryable={}, ms={}, attempt={}, detail={}",
                        status, code, retryable, ms, attempt, sanitized);
                last = new OneEmbeddingException(status, code, retryable, "one_ embed HTTP " + status + ": " + sanitized);
                if (!retryable || attempt == maxAttempts) {
                    throw last;
                }
            } catch (HttpTimeoutException ex) {
                // 读取超时：请求已发送、结果不确定，默认不重试（对齐 retry-on-read-timeout=false）。
                boolean retryable = properties.isRetryOnReadTimeout();
                log.warn("one_ embed 读取超时: ms={}, retryable={}, attempt={}", (System.nanoTime() - start) / 1_000_000, retryable, attempt);
                last = new OneEmbeddingException(0, "READ_TIMEOUT", retryable, "one_ embed 读取超时（" + properties.getReadTimeout().toMillis() + "ms）");
                if (!retryable || attempt == maxAttempts) {
                    throw last;
                }
            } catch (IOException ex) {
                // 连接类 IO（含 ConnectException）：可重试；其他 IO 保守视为可重试网络错误。
                boolean connect = ex instanceof ConnectException;
                log.warn("one_ embed 网络失败: type={}, connect={}, attempt={}", ex.getClass().getSimpleName(), connect, attempt);
                last = new OneEmbeddingException(0, "NETWORK", true, "one_ embed 网络失败: " + ex.getClass().getSimpleName());
                if (attempt == maxAttempts) {
                    throw last;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new OneEmbeddingException(0, "INTERRUPTED", false, "one_ embed 被中断");
            }
            backoff(attempt, deadlineNanos);
        }
        throw last != null ? last : new OneEmbeddingException(0, "REMOTE_ERROR", false, "one_ embed 失败（无明细）");
    }

    /** 构造请求体：model/input/encoding_format，dimensions 参数按开关携带。 */
    private String buildRequestBody(List<String> texts) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", properties.getModel());
        ArrayNode input = root.putArray("input");
        for (String text : texts) {
            input.add(text);
        }
        root.put("encoding_format", "float");
        if (properties.isSendDimensionsParam()) {
            root.put("dimensions", properties.getDimensions());
        }
        return root.toString();
    }

    /**
     * 解析 200 响应：data 按 index 排序、数量一致、维度一致、无 NaN/Inf；usage 真实累计。
     */
    private List<float[]> parseResponse(String body, int expectedCount) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException ex) {
            throw new OneEmbeddingException(200, "BAD_RESPONSE", false, "one_ embed 响应非法 JSON");
        }
        JsonNode usage = root.get("usage");
        if (usage != null && usage.hasNonNull("total_tokens")) {
            totalTokens.add(usage.get("total_tokens").asLong());
        } else {
            usageUnavailableCalls.increment();
        }
        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != expectedCount) {
            throw new OneEmbeddingException(200, "BAD_RESPONSE", false,
                    "one_ embed 响应 data 数量不符：期望 " + expectedCount + "，实际 " + (data == null ? "null" : data.size()));
        }
        float[][] ordered = new float[expectedCount][];
        for (JsonNode item : data) {
            int index = item.hasNonNull("index") ? item.get("index").asInt(-1) : -1;
            if (index < 0 || index >= expectedCount) {
                throw new OneEmbeddingException(200, "BAD_RESPONSE", false, "one_ embed 响应 index 非法: " + index);
            }
            JsonNode emb = item.get("embedding");
            if (emb == null || !emb.isArray() || emb.size() != properties.getDimensions()) {
                throw new OneEmbeddingException(200, "DIMENSION_MISMATCH", false,
                        "one_ embed 响应维度不符：期望 " + properties.getDimensions() + "，实际 " + (emb == null ? "null" : emb.size()));
            }
            float[] vector = new float[emb.size()];
            boolean allZero = true;
            for (int i = 0; i < emb.size(); i++) {
                double v = emb.get(i).asDouble();
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new OneEmbeddingException(200, "BAD_RESPONSE", false, "one_ embed 响应第 " + i + " 维非有限值");
                }
                vector[i] = (float) v;
                if (vector[i] != 0.0f) {
                    allZero = false;
                }
            }
            if (allZero) {
                throw new OneEmbeddingException(200, "ZERO_VECTOR", false, "one_ embed 返回零向量（语义空向量），拒绝写入");
            }
            if (ordered[index] != null) {
                throw new OneEmbeddingException(200, "BAD_RESPONSE", false, "one_ embed 响应 index 重复: " + index);
            }
            ordered[index] = vector;
        }
        List<float[]> result = new ArrayList<>(expectedCount);
        for (float[] vector : ordered) {
            if (vector == null) {
                throw new OneEmbeddingException(200, "BAD_RESPONSE", false, "one_ embed 响应 index 缺失");
            }
            result.add(vector);
        }
        return result;
    }

    /** 指数退避 + 抖动，受总墙钟约束；退避后若已超 deadline 则直接抛出。 */
    private void backoff(int attempt, long deadlineNanos) {
        double base = properties.getInitialBackoffMs() * Math.pow(properties.getBackoffMultiplier(), attempt - 1.0);
        long interval = (long) Math.min(base, properties.getMaxBackoffMs());
        double jitter = properties.getJitterFactor();
        if (jitter > 0) {
            double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-jitter, jitter);
            interval = Math.max(0L, (long) (interval * factor));
        }
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remainingMs <= 0) {
            throw new OneEmbeddingException(0, "DEADLINE_EXCEEDED", false,
                    "one_ embed 总墙钟超限（" + properties.getTotalDeadline().toMillis() + "ms）");
        }
        interval = Math.min(interval, remainingMs);
        try {
            Thread.sleep(interval);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OneEmbeddingException(0, "INTERRUPTED", false, "one_ embed 退避被中断");
        }
    }

    /** HTTP 状态 → 稳定错误码。 */
    private String classifyErrorCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401, 403 -> "AUTH_FAILED";
            case 404 -> "MODEL_NOT_FOUND";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "REMOTE_ERROR" : "HTTP_" + status;
        };
    }

    /**
     * 错误体脱敏：剔除密钥本体、Bearer 令牌、sk- 模式，截断 ≤500 字符。
     * 只在本类内实现，不修改既有 LogMaskingUtils 行为（H1）。
     */
    private String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "(empty)";
        }
        String s = raw;
        String key = properties.getApiKey();
        if (key != null && !key.isEmpty()) {
            s = s.replace(key, "***API_KEY***");
        }
        s = s.replaceAll("Bearer\\s+\\S+", "Bearer ***");
        s = s.replaceAll("sk-[A-Za-z0-9]{16,}", "sk-***");
        if (s.length() > 500) {
            s = s.substring(0, 500) + "...(truncated)";
        }
        return s;
    }

    /** 真实 HTTP 调用计数（含重试物理请求）。 */
    public long getCallCount() {
        return callCount.sum();
    }

    /** API usage.total_tokens 真实累计。 */
    public long getTotalTokens() {
        return totalTokens.sum();
    }

    /** 未返回 usage 的调用数。 */
    public long getUsageUnavailableCalls() {
        return usageUnavailableCalls.sum();
    }
}