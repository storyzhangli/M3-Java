package oneeval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * one_ 轨检索后端抽象与唯一实现：ONE_PGVECTOR（经应用 one_ HTTP 链路）。
 *
 * <p>D38 改造（相对 D37 d37eval）：删除 IN_MEMORY_LOCAL 内存降级后端——
 * 神经 Embedding 无法本地复刻，one_ 轨后端只有 ONE_PGVECTOR / BLOCKED / UNREACHABLE；
 * 端点前缀参数化为 /api/v1/one/...；新增 usage 计数端点读取（真实 LongAdder 累计）。</p>
 *
 * <p>硬口径：耗时字段 vectorSearchMs 为真实 PG 检索段；结果带 retrievalBackend 标记。</p>
 */
public final class Backends {

    /** one_ 端点前缀（改造点：D37 为 /api/v1，D38 one_ 轨为 /api/v1/one）。 */
    public static final String ONE_API_PREFIX = "/api/v1/one";

    /** 单条命中（逻辑 ID 映射后；映射失败 logicalChunkId=null）。 */
    public record Hit(int rank, String vectorChunkId, String logicalChunkId, String documentId,
                      String title, String content, Double similarity, Double distance,
                      Integer page, boolean mappingAmbiguous) {
    }

    /** 检索结果：SUCCESS/EMPTY/FAILED/TIMEOUT 严格区分。 */
    public record SearchOutcome(String status, List<Hit> hits, double queryEmbeddingMs,
                                Double vectorSearchMs, Double inMemorySearchMs,
                                double retrievalTotalMs, String errorReason, String reasonCode) {
        static SearchOutcome failed(String reason, String code) {
            return new SearchOutcome("FAILED", List.of(), 0, null, null, 0, reason, code);
        }
    }

    /** /one/rag/ask 来源视图（显式字段契约）。 */
    public record SourceView(String sourceId, String citationId, String sourceRef, String chunkId,
                             String documentId, String title, Integer page, Integer lastPage,
                             Integer paragraphIndex, String quotedSnippet, Boolean cited,
                             Double similarity, Double distance, String url) {
    }

    /** /one/rag/ask 结果。 */
    public record AskOutcome(String status, String decision, String retrievalStatus,
                             String generationStatus, String errorCode, String answer,
                             List<SourceView> sources, int retrievedCount, int evidenceCount,
                             boolean unknownCitationDetected, String clarificationQuestion,
                             List<String> missingInformation, int conflictGroupCount,
                             List<String> rejectedEvidenceIds, List<String> citationNotes,
                             Long retrievalMs, Long assemblyMs, Long generationMs, Long totalMs,
                             String errorReason) {
    }

    /** one_ usage 观测值（应用真实累计，非估算）。 */
    public record UsageSnapshot(long calls, long totalTokens, long usageUnavailableCalls,
                                String vectorSpaceId, boolean reachable) {
        static UsageSnapshot unreachable() {
            return new UsageSnapshot(0, 0, 0, null, false);
        }
    }

    public interface RetrievalBackend {
        String name();

        SearchOutcome search(String query, int topK, double threshold);
    }

    // ------------------------------------------------------------------
    // ONE_PGVECTOR：经应用真实 one_ HTTP 链路（/one/retrieval/search 与 /one/rag/ask）
    // ------------------------------------------------------------------
    public static final class HttpBackend implements RetrievalBackend {
        private final HttpClient client;
        private final ObjectMapper mapper;
        private final String baseUrl;
        private final Corpus corpus;
        private final int retrievalTimeoutMs;
        private final int e2eTimeoutMs;

        public HttpBackend(String baseUrl, Corpus corpus, ObjectMapper mapper,
                           int retrievalTimeoutMs, int e2eTimeoutMs) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.corpus = corpus;
            this.mapper = mapper;
            this.retrievalTimeoutMs = retrievalTimeoutMs;
            this.e2eTimeoutMs = e2eTimeoutMs;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.min(retrievalTimeoutMs, 10_000)))
                    .build();
        }

        @Override
        public String name() {
            return EnvProbe.BACKEND_ONE_PGVECTOR;
        }

        public boolean appUp() {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/documents"))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() == 200;
            } catch (Exception ex) {
                return false;
            }
        }

        /** one_ 轨是否开启：usage 端点 200 且业务码 200（条件装配，关闭时 404）。 */
        public boolean oneEndpointOk() {
            return usage().reachable();
        }

        /** 读取 one_ 真实调用计数（GET /api/v1/one/embeddings/usage）。 */
        public UsageSnapshot usage() {
            try {
                HttpRequest req = HttpRequest.newBuilder(
                                URI.create(baseUrl + ONE_API_PREFIX + "/embeddings/usage"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    return UsageSnapshot.unreachable();
                }
                JsonNode root = mapper.readTree(resp.body());
                if (root.path("code").asInt() != 200) {
                    return UsageSnapshot.unreachable();
                }
                JsonNode d = root.path("data");
                return new UsageSnapshot(d.path("calls").asLong(0), d.path("totalTokens").asLong(0),
                        d.path("usageUnavailableCalls").asLong(0),
                        d.path("vectorSpaceId").asText(null), true);
            } catch (Exception ex) {
                return UsageSnapshot.unreachable();
            }
        }

        @Override
        public SearchOutcome search(String query, int topK, double threshold) {
            long wallStart = System.nanoTime();
            try {
                String body = mapper.writeValueAsString(java.util.Map.of(
                        "query", query, "topK", topK, "threshold", threshold));
                JsonNode data = post(ONE_API_PREFIX + "/retrieval/search", body, retrievalTimeoutMs);
                if (data == null) {
                    return SearchOutcome.failed("检索接口返回非 200 业务码", "RETRIEVAL_BACKEND_FAIL");
                }
                List<Hit> hits = new ArrayList<>();
                int rank = 0;
                for (JsonNode m : data.path("matches")) {
                    rank++;
                    String content = m.path("content").asText(null);
                    String documentId = m.path("documentId").asText(null);
                    Corpus.Chunk mapped = content == null ? null : corpus.mapHit(documentId, content);
                    Double sim = m.hasNonNull("similarity") ? m.get("similarity").asDouble() : null;
                    Double dist = m.hasNonNull("distance") ? m.get("distance").asDouble() : null;
                    Integer page = m.hasNonNull("page") ? m.get("page").asInt() : null;
                    hits.add(new Hit(rank, m.path("chunkId").asText(null),
                            mapped == null ? null : mapped.logicalChunkId(), documentId,
                            m.path("title").asText(null), content, sim, dist, page, mapped == null));
                }
                double embedMs = data.path("queryEmbeddingMs").asDouble(0);
                Double searchMs = data.hasNonNull("vectorSearchMs") ? data.get("vectorSearchMs").asDouble() : null;
                double totalMs = data.path("retrievalTotalMs").asDouble(ms(System.nanoTime() - wallStart));
                return new SearchOutcome(hits.isEmpty() ? "EMPTY" : "SUCCESS", hits, embedMs,
                        searchMs, null, totalMs, null, null);
            } catch (HttpTimeoutException ex) {
                return new SearchOutcome("TIMEOUT", List.of(), 0, null, null,
                        ms(System.nanoTime() - wallStart), "检索超时", "TIMEOUT_RETRIEVAL");
            } catch (Exception ex) {
                String msg = String.valueOf(ex.getMessage());
                String code = msg.contains("向量") ? "RETRIEVAL_EMBED_FAIL" : "RETRIEVAL_BACKEND_FAIL";
                return SearchOutcome.failed("检索执行异常: " + ex.getClass().getSimpleName(), code);
            }
        }

        /** one_ RAG 问答（决策路径）；离线 mock 下生成类为 NOT_CONFIGURED，不调模型。 */
        public AskOutcome ask(String question, int topK) {
            long wallStart = System.nanoTime();
            try {
                String body = mapper.writeValueAsString(java.util.Map.of("question", question, "topK", topK));
                JsonNode data = post(ONE_API_PREFIX + "/rag/ask", body, e2eTimeoutMs);
                if (data == null) {
                    return new AskOutcome("FAILED", null, null, null, null, null, List.of(),
                            0, 0, false, null, List.of(), 0, List.of(), List.of(),
                            null, null, null, Math.round(ms(System.nanoTime() - wallStart)),
                            "问答接口返回非 200 业务码");
                }
                List<SourceView> sources = new ArrayList<>();
                for (JsonNode s : data.path("sources")) {
                    sources.add(new SourceView(
                            s.path("sourceId").asText(null), s.path("citationId").asText(null),
                            s.path("sourceRef").asText(null), s.path("chunkId").asText(null),
                            s.path("documentId").asText(null), s.path("title").asText(null),
                            s.hasNonNull("page") ? s.get("page").asInt() : null,
                            s.hasNonNull("lastPage") ? s.get("lastPage").asInt() : null,
                            s.hasNonNull("paragraphIndex") ? s.get("paragraphIndex").asInt() : null,
                            s.path("quotedSnippet").asText(null),
                            s.hasNonNull("cited") ? s.get("cited").asBoolean() : null,
                            s.hasNonNull("similarity") ? s.get("similarity").asDouble() : null,
                            s.hasNonNull("distance") ? s.get("distance").asDouble() : null,
                            s.path("url").asText(null)));
                }
                JsonNode timing = data.path("timing");
                return new AskOutcome("SUCCESS",
                        data.path("decision").asText(null),
                        data.path("retrievalStatus").asText(null),
                        data.path("generationStatus").asText(null),
                        data.path("errorCode").asText(null),
                        data.path("answer").asText(null),
                        sources, data.path("retrievedCount").asInt(0),
                        data.path("evidenceCount").asInt(0),
                        data.path("unknownCitationDetected").asBoolean(false),
                        data.path("clarificationQuestion").asText(null),
                        textList(data.path("missingInformation")),
                        data.path("conflictGroups").size(),
                        textList(data.path("rejectedEvidenceIds")),
                        textList(data.path("citationNotes")),
                        timing.hasNonNull("retrievalMs") ? timing.get("retrievalMs").asLong() : null,
                        timing.hasNonNull("assemblyMs") ? timing.get("assemblyMs").asLong() : null,
                        timing.hasNonNull("generationMs") ? timing.get("generationMs").asLong() : null,
                        timing.hasNonNull("totalMs") ? timing.get("totalMs").asLong() : null,
                        null);
            } catch (HttpTimeoutException ex) {
                return new AskOutcome("TIMEOUT", null, null, null, null, null, List.of(),
                        0, 0, false, null, List.of(), 0, List.of(), List.of(),
                        null, null, null, Math.round(ms(System.nanoTime() - wallStart)), "端到端超时");
            } catch (Exception ex) {
                return new AskOutcome("FAILED", null, null, null, null, null, List.of(),
                        0, 0, false, null, List.of(), 0, List.of(), List.of(),
                        null, null, null, Math.round(ms(System.nanoTime() - wallStart)),
                        "问答执行异常: " + ex.getClass().getSimpleName());
            }
        }

        /** 统一 POST：Result 包装 code=200 才返回 data；业务错误抛带 msg 的异常。 */
        private JsonNode post(String path, String body, int timeoutMs) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root.path("code").asInt() != 200) {
                throw new IllegalStateException("业务码 " + root.path("code").asInt()
                        + ": " + root.path("msg").asText(""));
            }
            return root.path("data");
        }

        private static List<String> textList(JsonNode n) {
            List<String> out = new ArrayList<>();
            if (n != null && n.isArray()) {
                n.forEach(x -> out.add(x.asText()));
            }
            return out;
        }
    }

    /** 稳定排序：相似度降序；同分以稳定逻辑 chunkId 升序作次级键（跨进程一致）。 */
    public static void sortHits(List<Hit> hits) {
        hits.sort(Comparator.comparingDouble((Hit h) -> h.similarity() == null ? 0 : h.similarity())
                .reversed()
                .thenComparing(Hit::logicalChunkId));
    }

    static double ms(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }

    private Backends() {
    }
}