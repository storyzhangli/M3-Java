package com.zl.d44.rag.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d44.rag.rerank.RerankHttpStatusException;
import com.zl.d44.rag.rerank.RerankPort;
import com.zl.d44.rag.rerank.RerankProperties;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DashScope 远程精排适配器（A2 臂，gte-rerank-v2）。
 *
 * <p>装配前提（H2 双重门禁）：{@code rerank.enabled=true} 且 {@code strategy=remote}
 * 且 {@code remote.enabled=true}——远程臂必须显式注入 {@code RERANK_REMOTE_ENABLED=true}
 * 与 {@code DASHSCOPE_API_KEY}（缺省零远程，H9）。Key 仅经配置绑定环境变量传入，
 * 永不写入日志/证据。</p>
 *
 * <p>韧性口径（D5/§3.3，E1/E2/E5）：永久错误（鉴权/参数/4xx/配额类）不重试；
 * 瞬时错误（超时/连接/429/5xx）应用层<b>恰好一次</b>重试（对齐 maxAttempts=1 关内置
 * 重试约定，重试不放大）；任何最终失败回退原始向量序 + {@link RerankFlags#FAILED_OPEN}，
 * 绝不把检索打成 FAILED。</p>
 *
 * <p>预算三重帽（KP4 冻结，E5/熔断#6）：实例级累计 calls / 实测 totalTokens / 费用
 * 触顶后本实例不再发起远程调用，直接 {@link RerankFlags#BUDGET_STOP} 回退。</p>
 *
 * <p><b>D-EX8 通道偏差</b>：dashscope-sdk-java 2.23.6 的 {@code TextReRank} 全部公开构造
 * 在本环境实测抛 NullPointerException（SynchronizeHalfDuplexApi 读 ServiceOption.getProtocol()
 * 为 null，探针 docs/temp_docs/D44/probe/D44RerankProbe.java 留证），SDK 封装路径不可用。
 * 本适配器改走<b>百炼原生 REST 端点</b>（JDK HttpClient + Jackson，协议字段与官方
 * text-rerank API 一致），重试/帽/截断/fail-open 语义保持不变。</p>
 *
 * <p>截断规则（E4，口径冻结于 rerank-protocol.truncationLimits）：单文档估算
 * token=ceil(chars/1.3)，超过 4000 时输入侧按字符上限截断并标 {@link RerankFlags#TRUNCATED}；
 * 请求估算总量超过 30000 预检拒绝并标 {@link RerankFlags#OVERSIZED} 回退原序。</p>
 */
@Slf4j
public class DashScopeRerankAdapter implements RerankPort {

    private final RerankProperties properties;
    private final RerankExceptionClassifier classifier;

    /** 预算累计帽（实例级；单实例生命周期内跨题目累计，实验脚本重启实例即重置）。 */
    private final AtomicInteger totalCalls = new AtomicInteger();
    private final AtomicLong totalMeasuredTokens = new AtomicLong();
    private final AtomicLong totalCostMicroCny = new AtomicLong();

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public RerankResult rerank(String query, List<RerankCandidate> candidates, int topN) {
        long start = System.nanoTime();
        RerankProperties.Remote remote = properties.getRemote();
        if (candidates.isEmpty()) {
            return RerankResult.identity("dashscope", candidates, RerankUsage.zero(0),
                    elapsedMs(start), EnumSet.of(RerankFlags.NOOP_EMPTY));
        }

        int estimated = NoopRerankAdapter.estimateTokens(candidates);

        // 1) 请求级预检（E4：OVERSIZED 回退，不发起调用、不计帽）
        if (estimated > remote.getRequestTokenLimit()) {
            log.warn("rerank(remote) 请求估算 token={} 超上限={}，预检回退原序",
                    estimated, remote.getRequestTokenLimit());
            return RerankResult.identity("dashscope", candidates, RerankUsage.zero(estimated),
                    elapsedMs(start), EnumSet.of(RerankFlags.OVERSIZED));
        }

        // 2) 预算三重帽（E5：任何一帽触顶即停发远程调用）
        if (isBudgetExhausted(remote)) {
            log.warn("rerank(remote) 预算帽触顶 calls={} measuredTokens={} costMicroCny={}，回退原序",
                    totalCalls.get(), totalMeasuredTokens.get(), totalCostMicroCny.get());
            return RerankResult.identity("dashscope", candidates,
                    usageSnapshot(estimated, null, null),
                    elapsedMs(start), EnumSet.of(RerankFlags.BUDGET_STOP));
        }

        // 3) 缺 Key 直接 fail-open（不发调用；NoApiKey 属永久错误，E1 语义）
        if (remote.getApiKey() == null || remote.getApiKey().isBlank()) {
            log.warn("rerank(remote) 未注入 DASHSCOPE_API_KEY，fail-open 回退原序");
            return RerankResult.identity("dashscope", candidates, RerankUsage.zero(estimated),
                    elapsedMs(start), EnumSet.of(RerankFlags.FAILED_OPEN));
        }

        // 4) 输入侧单文档截断（E4：TRUNCATED）
        int charLimit = (int) Math.floor(remote.getPerDocTokenLimit() * remote.getTokenCharDivisor());
        List<String> documents = new ArrayList<>(candidates.size());
        boolean truncated = false;
        for (RerankCandidate c : candidates) {
            String content = c.contentOrEmpty();
            if (content.length() > charLimit) {
                documents.add(content.substring(0, charLimit));
                truncated = true;
            } else {
                documents.add(content);
            }
        }
        if (truncated) {
            log.info("rerank(remote) 存在超 {} 字符候选，输入侧截断（estimated token 口径 chars/{}）",
                    charLimit, remote.getTokenCharDivisor());
        }

        int effectiveTopN = resolveOutputLimit(candidates.size(), topN);
        EnumSet<RerankFlags> flags = EnumSet.noneOf(RerankFlags.class);
        if (truncated) {
            flags.add(RerankFlags.TRUNCATED);
        }

        // 5) 调用 + 瞬时单次重试（E2：重试次数硬编码至多 1）
        HttpUpstream result;
        try {
            try {
                result = callOnce(query, documents, remote, effectiveTopN);
            } catch (Exception first) {
                if (classifier.classify(first) == RerankExceptionClassifier.Verdict.TRANSIENT) {
                    flags.add(RerankFlags.RETRIED);
                    log.info("rerank(remote) 瞬时错误已分类，执行应用层单次重试（异常类型 {}）",
                            first.getClass().getSimpleName());
                    result = callOnce(query, documents, remote, effectiveTopN);
                } else {
                    throw first;
                }
            }
        } catch (Exception fatal) {
            // 永久错误、或重试后仍失败：fail-open 回退原序（H8）。日志仅异常类型与状态码，零 Key 零内容。
            flags.add(RerankFlags.FAILED_OPEN);
            log.error("rerank(remote) 最终失败，回退原始向量序，异常类型: {}",
                    fatal.getClass().getSimpleName());
            return RerankResult.identity("dashscope", candidates,
                    usageSnapshot(estimated, null, null), elapsedMs(start), flags);
        }

        // 6) 响应有效性（HTTP 通道：非 200 已在 callOnce 抛出；此处校验响应体结构完整性）
        Integer statusCode = result.statusCode();
        String upstreamCode = result.upstreamCode();
        if (statusCode == null || statusCode != 200 || result.results() == null) {
            flags.add(RerankFlags.FAILED_OPEN);
            log.error("rerank(remote) 非成功响应形态 status={} code={}，回退原序", statusCode, upstreamCode);
            return RerankResult.identity("dashscope", candidates,
                    usageSnapshot(estimated, statusCode, upstreamCode), elapsedMs(start), flags);
        }

        // 7) 记账：实测 token 与费用（H4：费用=实测 totalTokens×官方单价，无实测不伪造）
        Integer measured = result.totalTokens();
        if (measured != null) {
            totalMeasuredTokens.addAndGet(measured);
            totalCostMicroCny.addAndGet(Math.round(measured * properties.getRemote()
                    .getPriceCnyPerMillionTokens() / 1_000_000.0d * 1_000_000.0d));
        }
        // 实验审计观测行（A2 费用/帽耗的权威提取点，D7 口径；仅数值，零 Key 零内容）
        log.info("rerank(remote) 记账: thisMeasuredTokens={}, cumulativeCalls={},"
                        + " cumulativeMeasuredTokens={}, cumulativeCostCny={}",
                measured, totalCalls.get(), totalMeasuredTokens.get(),
                totalCostMicroCny.get() / 1_000_000.0d);

        // 8) 结果映射：index → 候选 ordinal；本地按 relevanceScore 降序稳定排序
        //   （不依赖上游返回序承诺；同分按原向量序决胜，保证可复算）
        List<JsonNode> upstream = result.results();
        List<RerankResult.Item> items = new ArrayList<>(upstream.size());
        for (JsonNode r : upstream) {
            int idx = r.hasNonNull("index") ? r.get("index").asInt() : -1;
            if (idx < 0 || idx >= candidates.size()) {
                continue; // 越界 index 丢弃（防御；不影响原序回退安全性）
            }
            double score = r.hasNonNull("relevance_score") ? r.get("relevance_score").asDouble() : 0.0d;
            items.add(new RerankResult.Item(candidates.get(idx).ordinal(), 0, score));
        }
        items.sort(Comparator.comparingDouble(RerankResult.Item::score).reversed()
                .thenComparingInt(RerankResult.Item::candidateOrdinal));
        if (items.isEmpty()) {
            flags.add(RerankFlags.FAILED_OPEN);
            return RerankResult.identity("dashscope", candidates,
                    usageSnapshot(estimated, statusCode, upstreamCode), elapsedMs(start), flags);
        }
        if (items.size() > effectiveTopN) {
            items = new ArrayList<>(items.subList(0, effectiveTopN));
            flags.add(RerankFlags.OUTPUT_TRUNCATED);
        }
        List<RerankResult.Item> ordered = new ArrayList<>(items.size());
        boolean changed = false;
        for (int rank = 0; rank < items.size(); rank++) {
            RerankResult.Item src = items.get(rank);
            ordered.add(new RerankResult.Item(src.candidateOrdinal(), rank, src.score()));
            if (rank < candidates.size() && src.candidateOrdinal() != candidates.get(rank).ordinal()) {
                changed = true;
            }
        }
        flags.add(changed ? RerankFlags.REORDERED : RerankFlags.IDENTITY);
        return new RerankResult("dashscope", List.copyOf(ordered),
                usageSnapshot(estimated, statusCode, upstreamCode), elapsedMs(start), flags);
    }

    /** 实例级共享 HttpClient（连接复用；读超时按请求粒度设置，per-request 权威）。 */
    private final HttpClient httpClient;

    /** 原生 HTTP 通道的解析后响应（结构对齐官方 text-rerank 响应体，不做二次加工）。 */
    record HttpUpstream(Integer statusCode, String upstreamCode, Integer totalTokens, List<JsonNode> results) {
    }

    public DashScopeRerankAdapter(RerankProperties properties, RerankExceptionClassifier classifier) {
        this.properties = properties;
        this.classifier = classifier;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getRemote().getConnectTimeoutMs()))
                .build();
    }

    /** 单次远程 call（记帽增量在发起前——即使失败也计入 call 数，保守防放大）。 */
    private HttpUpstream callOnce(String query, List<String> documents,
                                  RerankProperties.Remote remote, int topN) throws Exception {
        totalCalls.incrementAndGet();
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", remote.getModel());
        ObjectNode input = payload.putObject("input");
        input.put("query", query);
        var docArray = input.putArray("documents");
        documents.forEach(docArray::add);
        ObjectNode params = payload.putObject("parameters");
        params.put("top_n", topN);
        params.put("return_documents", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(remote.getBaseUrl()))
                .timeout(Duration.ofMillis(remote.getReadTimeoutMs()))
                .header("Authorization", "Bearer " + remote.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        JsonNode root;
        try {
            root = JSON.readTree(response.body());
        } catch (Exception parseFail) {
            // 非 JSON 响应体（网关 HTML 错误页等）：按上游状态裁决，不解析正文。
            throw new RerankHttpStatusException(status, "UNPARSEABLE_BODY");
        }
        String upstreamCode = root.hasNonNull("code") ? root.get("code").asText() : null;
        if (status != 200) {
            // 日志零正文零 Key：状态码+错误码经异常类型面留给上层单行记录。
            throw new RerankHttpStatusException(status, upstreamCode);
        }
        JsonNode output = root.get("output");
        JsonNode results = output != null && output.hasNonNull("results") ? output.get("results") : null;
        Integer totalTokens = root.hasNonNull("usage") && root.get("usage").hasNonNull("total_tokens")
                ? root.get("usage").get("total_tokens").asInt() : null;
        List<JsonNode> list = new ArrayList<>();
        if (results != null && results.isArray()) {
            results.forEach(list::add);
        } else {
            results = null;
        }
        return new HttpUpstream(status, upstreamCode, totalTokens, results == null ? null : list);
    }

    private boolean isBudgetExhausted(RerankProperties.Remote remote) {
        return totalCalls.get() >= remote.getBudgetMaxCalls()
                || totalMeasuredTokens.get() >= remote.getBudgetMaxTokens()
                || totalCostMicroCny.get() >= Math.round(remote.getBudgetMaxCny() * 1_000_000.0d);
    }

    private RerankUsage usageSnapshot(int estimatedTokens, Integer statusCode, String upstreamCode) {
        Double cost = totalMeasuredTokens.get() > 0
                ? totalCostMicroCny.get() / 1_000_000.0d : null;
        return new RerankUsage(totalCalls.get(),
                totalMeasuredTokens.get() > 0 ? Math.toIntExact(totalMeasuredTokens.get()) : null,
                estimatedTokens, cost, statusCode, upstreamCode);
    }

    private int resolveOutputLimit(int candidateCount, int topN) {
        int limit = topN <= 0 ? candidateCount : Math.min(topN, candidateCount);
        int maxOutput = properties.getMaxOutput();
        return maxOutput > 0 ? Math.min(limit, maxOutput) : limit;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
