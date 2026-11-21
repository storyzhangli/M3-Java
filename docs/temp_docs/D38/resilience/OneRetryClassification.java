import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.zl.d38.one.client.OneBailianEmbeddingClient;
import com.zl.d38.one.client.OneEmbeddingException;
import com.zl.d38.one.config.OneEmbeddingProperties;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * D38 C16 重试分类受控注入测试（零真实远程调用）。
 *
 * <p>用本地 com.sun.net.httpserver 桩服务器对 OneBailianEmbeddingClient 注入受控错误：
 * 400/401 不重试、429/503 有限重试（恢复/耗尽）、连接拒绝、读取超时不重试、
 * 错误体脱敏、usage 真实累计、零向量/维度不符/超批拒绝。全部指向 127.0.0.1 桩，
 * 不触碰百炼真实端点，不消耗 ONE_CALL_BUDGET。</p>
 *
 * <p>运行：java -cp <prod classes;jackson;slf4j-api;本目录classes> OneRetryClassification <输出json路径></p>
 */
public class OneRetryClassification {

    static final String FAKE_KEY = "stub-key-0123456789abcdef";
    static final ObjectMapper M = new ObjectMapper();
    static final List<ObjectNode> RESULTS = new ArrayList<>();
    static int pass = 0, fail = 0;

    record Resp(int status, String body, long delayMs) {}

    /** 脚本化桩服务器：按序返回响应，脚本耗尽后重复最后一个；hits 计物理请求数。 */
    static class Stub implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();
        final Resp[] script;
        Stub(Resp... script) throws Exception {
            this.script = script;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/embeddings", ex -> {
                int i = hits.getAndIncrement();
                Resp r = script[Math.min(i, script.length - 1)];
                try {
                    ex.getRequestBody().readAllBytes();
                    if (r.delayMs() > 0) Thread.sleep(r.delayMs());
                    byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(r.status(), b.length);
                    try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally { ex.close(); }
            });
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }

    static OneEmbeddingProperties props(String baseUrl) {
        OneEmbeddingProperties p = new OneEmbeddingProperties();
        p.setEnabled(true);
        p.setBaseUrl(baseUrl);
        p.setApiKey(FAKE_KEY);
        p.setDimensions(4);
        p.setMaxBatch(16);
        p.setMaxAttempts(2);
        p.setInitialBackoffMs(50);
        p.setReadTimeout(Duration.ofSeconds(30));
        p.setConnectTimeout(Duration.ofSeconds(3));
        p.setTotalDeadline(Duration.ofSeconds(10));
        return p;
    }

    static String okBody(int dims, Long tokens, boolean zero) {
        ObjectNode root = M.createObjectNode();
        ArrayNode data = root.putArray("data");
        ObjectNode item = data.addObject();
        item.put("index", 0);
        ArrayNode emb = item.putArray("embedding");
        for (int i = 0; i < dims; i++) emb.add(zero ? 0.0 : 0.1 * (i + 1));
        if (tokens != null) root.putObject("usage").put("total_tokens", tokens);
        return root.toString();
    }

    static void check(String id, String name, boolean ok, String detail) {
        ObjectNode n = M.createObjectNode();
        n.put("id", id); n.put("name", name); n.put("pass", ok); n.put("detail", detail);
        RESULTS.add(n);
        if (ok) pass++; else fail++;
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + id + " " + name + " — " + detail);
    }

    static OneEmbeddingException expectFail(OneBailianEmbeddingClient c, List<String> texts) {
        try {
            c.embed(texts);
            return null;
        } catch (OneEmbeddingException e) {
            return e;
        }
    }

    public static void main(String[] args) throws Exception {
        List<String> one = List.of("受控注入测试文本");

        // S1 400 → BAD_REQUEST 不重试（1 次物理请求）
        try (Stub s = new Stub(new Resp(400, "{\"error\":{\"message\":\"invalid parameter\"}}", 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            OneEmbeddingException e = expectFail(c, one);
            check("S1", "400不重试", e != null && "BAD_REQUEST".equals(e.getErrorCode())
                    && !e.isRetryable() && s.hits.get() == 1 && e.getHttpStatus() == 400,
                    "code=" + (e == null ? "null" : e.getErrorCode()) + " hits=" + s.hits.get());
        }
        // S2 401 → AUTH_FAILED 不重试
        try (Stub s = new Stub(new Resp(401, "{\"error\":\"unauthorized\"}", 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            OneEmbeddingException e = expectFail(c, one);
            check("S2", "401不重试", e != null && "AUTH_FAILED".equals(e.getErrorCode())
                    && !e.isRetryable() && s.hits.get() == 1,
                    "code=" + (e == null ? "null" : e.getErrorCode()) + " hits=" + s.hits.get());
        }
        // S3 429→200 重试恢复（2 次物理请求，退避可观测）
        try (Stub s = new Stub(new Resp(429, "{\"error\":\"rate limited\"}", 0),
                new Resp(200, okBody(4, 5L, false), 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            long t0 = System.nanoTime();
            List<float[]> v = c.embed(one);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("S3", "429重试恢复", v.size() == 1 && v.get(0).length == 4 && s.hits.get() == 2 && ms >= 35,
                    "hits=" + s.hits.get() + " elapsedMs=" + ms + " tokens=" + c.getTotalTokens());
        }
        // S4 503×2 重试耗尽 → REMOTE_ERROR retryable=true
        try (Stub s = new Stub(new Resp(503, "{\"error\":\"unavailable\"}", 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            OneEmbeddingException e = expectFail(c, one);
            check("S4", "503重试耗尽", e != null && "REMOTE_ERROR".equals(e.getErrorCode())
                    && e.isRetryable() && s.hits.get() == 2,
                    "code=" + (e == null ? "null" : e.getErrorCode()) + " hits=" + s.hits.get());
        }
        // S5 连接拒绝 → NETWORK 可重试（2 次尝试）
        int closedPort;
        try (ServerSocket ss = new ServerSocket(0)) { closedPort = ss.getLocalPort(); }
        OneBailianEmbeddingClient c5 = new OneBailianEmbeddingClient(props("http://127.0.0.1:" + closedPort));
        OneEmbeddingException e5 = expectFail(c5, one);
        check("S5", "连接拒绝重试", e5 != null && "NETWORK".equals(e5.getErrorCode())
                && e5.isRetryable() && c5.getCallCount() == 2,
                "code=" + (e5 == null ? "null" : e5.getErrorCode()) + " attempts=" + c5.getCallCount());
        // S6 读取超时 → READ_TIMEOUT 默认不重试（1 次物理请求）
        try (Stub s = new Stub(new Resp(200, okBody(4, 1L, false), 1500))) {
            OneEmbeddingProperties p = props(s.baseUrl());
            p.setReadTimeout(Duration.ofMillis(300));
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(p);
            OneEmbeddingException e = expectFail(c, one);
            check("S6", "读取超时不重试", e != null && "READ_TIMEOUT".equals(e.getErrorCode())
                    && !e.isRetryable() && s.hits.get() == 1,
                    "code=" + (e == null ? "null" : e.getErrorCode()) + " hits=" + s.hits.get());
        }
        // S7 错误体脱敏：密钥本体/Bearer/sk- 模式剔除 + 截断
        String dirty = "error key=" + FAKE_KEY + " Bearer faketoken123 sk-ABCDEFGHIJKLMNOPQRSTUVWX " + "x".repeat(600);
        try (Stub s = new Stub(new Resp(400, dirty, 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            OneEmbeddingException e = expectFail(c, one);
            String msg = e == null ? "" : e.getMessage();
            check("S7", "错误体脱敏", e != null && !msg.contains(FAKE_KEY) && msg.contains("***API_KEY***")
                    && msg.contains("Bearer ***") && msg.contains("sk-***") && msg.contains("...(truncated)"),
                    "masked=" + (e != null && !msg.contains(FAKE_KEY)) + " len=" + msg.length());
        }
        // S8 usage 真实累计 + USAGE_UNAVAILABLE 单独计数
        try (Stub s = new Stub(new Resp(200, okBody(4, 7L, false), 0),
                new Resp(200, okBody(4, null, false), 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            c.embed(one); c.embed(one);
            check("S8", "usage真实累计", c.getTotalTokens() == 7 && c.getUsageUnavailableCalls() == 1 && c.getCallCount() == 2,
                    "tokens=" + c.getTotalTokens() + " unavailable=" + c.getUsageUnavailableCalls());
        }
        // S9 零向量拒绝
        try (Stub s = new Stub(new Resp(200, okBody(4, 1L, true), 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            OneEmbeddingException e = expectFail(c, one);
            check("S9", "零向量拒绝", e != null && "ZERO_VECTOR".equals(e.getErrorCode()) && !e.isRetryable(),
                    "code=" + (e == null ? "null" : e.getErrorCode()));
        }
        // S10 维度不符拒绝
        try (Stub s = new Stub(new Resp(200, okBody(3, 1L, false), 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            OneEmbeddingException e = expectFail(c, one);
            check("S10", "维度不符拒绝", e != null && "DIMENSION_MISMATCH".equals(e.getErrorCode()) && !e.isRetryable(),
                    "code=" + (e == null ? "null" : e.getErrorCode()));
        }
        // S11 超批拒绝（零物理请求）
        try (Stub s = new Stub(new Resp(200, okBody(4, 1L, false), 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            List<String> batch = new ArrayList<>();
            for (int i = 0; i < 17; i++) batch.add("t" + i);
            OneEmbeddingException e = expectFail(c, batch);
            check("S11", "超批拒绝", e != null && "BATCH_TOO_LARGE".equals(e.getErrorCode())
                    && s.hits.get() == 0 && c.getCallCount() == 0,
                    "code=" + (e == null ? "null" : e.getErrorCode()) + " hits=" + s.hits.get());
        }
        // S12 429×2 耗尽 → RATE_LIMITED retryable=true
        try (Stub s = new Stub(new Resp(429, "{\"error\":\"rate limited\"}", 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            OneEmbeddingException e = expectFail(c, one);
            check("S12", "429重试耗尽", e != null && "RATE_LIMITED".equals(e.getErrorCode())
                    && e.isRetryable() && s.hits.get() == 2,
                    "code=" + (e == null ? "null" : e.getErrorCode()) + " hits=" + s.hits.get());
        }
        // S13 503→200 重试恢复
        try (Stub s = new Stub(new Resp(503, "{\"error\":\"unavailable\"}", 0),
                new Resp(200, okBody(4, 3L, false), 0))) {
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(props(s.baseUrl()));
            List<float[]> v = c.embed(one);
            check("S13", "503重试恢复", v.size() == 1 && s.hits.get() == 2 && c.getTotalTokens() == 3,
                    "hits=" + s.hits.get() + " tokens=" + c.getTotalTokens());
        }
        // S14 maxAttempts=1 时 503 不重试
        try (Stub s = new Stub(new Resp(503, "{\"error\":\"unavailable\"}", 0))) {
            OneEmbeddingProperties p = props(s.baseUrl());
            p.setMaxAttempts(1);
            OneBailianEmbeddingClient c = new OneBailianEmbeddingClient(p);
            OneEmbeddingException e = expectFail(c, one);
            check("S14", "maxAttempts=1不重试", e != null && "REMOTE_ERROR".equals(e.getErrorCode()) && s.hits.get() == 1,
                    "hits=" + s.hits.get());
        }

        ObjectNode summary = M.createObjectNode();
        summary.put("testSuite", "D38-C16 重试分类受控注入");
        summary.put("zeroRemoteCalls", true);
        summary.put("stub", "com.sun.net.httpserver @127.0.0.1 随机端口（脚本化响应）");
        summary.put("fakeKeyOnly", true);
        summary.put("passCount", pass);
        summary.put("failCount", fail);
        summary.put("allPass", fail == 0);
        summary.put("executedAt", OffsetDateTime.now().toString());
        ArrayNode arr = summary.putArray("scenarios");
        RESULTS.forEach(arr::add);
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        M.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), summary);
        System.out.println("[SUMMARY] PASS=" + pass + " FAIL=" + fail + " → " + out);
        System.exit(fail == 0 ? 0 : 1);
    }
}