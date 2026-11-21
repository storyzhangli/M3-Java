package hybrideval;

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
 * hybrideval 检索后端：只有 HTTP 一条路（任务书 P4.1，无内存兜底）。
 *
 * <p>两个策略端点同链路对照：</p>
 * <ul>
 *   <li>PURE_VECTOR：{@code POST /api/v1/retrieval/search} + {@code /api/v1/rag/ask}
 *       ——与 D37 冻结基线完全相同的请求体与解析，保证 H3 控制变量；</li>
 *   <li>HYBRID_RRF：{@code POST /api/v1/hybrid/retrieval/search} + {@code /api/v1/hybrid/rag/ask}
 *       ——响应为 RagRetrieval 同构 matches（score 槽位=RRI/RRF 语义分数，similarity=向量原值）
 *       加 fusionTrace/keywordMatches/timing 扩展，扩展原样存入 {@link SearchOutcome#extra()}，
 *       原始记录全量落盘，不进质量投影浮点。</li>
 * </ul>
 */
public final class Backends {

    public static final String STRATEGY_PURE_VECTOR = "PURE_VECTOR";
    public static final String STRATEGY_HYBRID_RRF = "HYBRID_RRF";

    /** 单条命中（逻辑 ID 映射后；映射失败 logicalChunkId=null）。 */
    public record Hit(int rank, String vectorChunkId, String logicalChunkId, String documentId,
                      String title, String content, Double similarity, Double distance,
                      Integer page, boolean mappingAmbiguous) {
    }

    /**
     * 检索结果：SUCCESS/EMPTY/FAILED/TIMEOUT 严格区分。
     *
     * @param extra hybrid 策略的原始 data 节点（fusionTrace/keywordMatches/扩展计时/raw matches）；
     *              向量策略为 null
     */
    public record SearchOutcome(String status, List<Hit> hits, double queryEmbeddingMs,
                                Double vectorSearchMs, Double inMemorySearchMs,
                                double retrievalTotalMs, String errorReason, String reasonCode,
                                JsonNode extra) {
        static SearchOutcome failed(String reason, String code) {
            return new SearchOutcome("FAILED", List.of(), 0, null, null, 0, reason, code, null);
        }
    }

    /** /rag/ask 来源视图（显式字段契约）。 */
    public record SourceView(String sourceId, String citationId, String sourceRef, String chunkId,
                             String documentId, String title, Integer page, Integer lastPage,
                             Integer paragraphIndex, String quotedSnippet, Boolean cited,
                             Double similarity, Double distance, String url) {
    }

    /** /rag/ask 结果。 */
    public record AskOutcome(String status, String decision, String retrievalStatus,
                             String generationStatus, String errorCode, String answer,
                             List<SourceView> sources, int retrievedCount, int evidenceCount,
                             boolean unknownCitationDetected, String clarificationQuestion,
                             List<String> missingInformation, int conflictGroupCount,
                             List<String> rejectedEvidenceIds, List<String> citationNotes,
                             Long retrievalMs, Long assemblyMs, Long generationMs, Long totalMs,
                             String errorReason, String retrievalStrategy) {
    }

    // ------------------------------------------------------------------
    // HTTP：经应用真实链路（两策略四个端点）
    // ------------------------------------------------------------------
    public static final class HttpBackend {
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

        /** 探测用轻量检索（任一端点 ok 判定；正式评测另行全量调用）。 */
        public boolean probeSearch(String path, String query) {
            try {
                String body = mapper.writeValueAsString(java.util.Map.of(
                        "query", query, "topK", 1, "threshold", 0.0));
                JsonNode data = post(path, body, retrievalTimeoutMs);
                return data != null;
            } catch (Exception ex) {
                return false;
            }
        }

        /** PURE_VECTOR：与 D37 Evaluator 完全同构的请求/解析。 */
        public SearchOutcome searchVector(String query, int topK, double threshold) {
            long wallStart = System.nanoTime();
            try {
                String body = mapper.writeValueAsString(java.util.Map.of(
                        "query", query, "topK", topK, "threshold", threshold));
                JsonNode data = post("/api/v1/retrieval/search", body, retrievalTimeoutMs);
                if (data == null) {
                    return SearchOutcome.failed("检索接口返回非 200 业务码", "RETRIEVAL_BACKEND_FAIL");
                }
                List<Hit> hits = parseMatches(data.path("matches"), false);
                double embedMs = data.path("queryEmbeddingMs").asDouble(0);
                Double searchMs = data.hasNonNull("vectorSearchMs") ? data.get("vectorSearchMs").asDouble() : null;
                double totalMs = data.path("retrievalTotalMs").asDouble(ms(System.nanoTime() - wallStart));
                return new SearchOutcome(hits.isEmpty() ? "EMPTY" : "SUCCESS", hits, embedMs,
                        searchMs, null, totalMs, null, null, null);
            } catch (HttpTimeoutException ex) {
                return new SearchOutcome("TIMEOUT", List.of(), 0, null, null,
                        ms(System.nanoTime() - wallStart), "检索超时", "TIMEOUT_RETRIEVAL", null);
            } catch (Exception ex) {
                String msg = String.valueOf(ex.getMessage());
                String code = msg.contains("向量") ? "RETRIEVAL_EMBED_FAIL" : "RETRIEVAL_BACKEND_FAIL";
                return SearchOutcome.failed("检索执行异常: " + ex.getClass().getSimpleName(), code);
            }
        }

        /**
         * HYBRID_RRF：POST /api/v1/hybrid/retrieval/search。
         * Hit.similarity 槽位装 RRF 融合分（score 字段），向量原值与 hitSource/matchedTerms
         * 保留在 extra.rawMatches，由 Evaluator 合并进原始记录（不进质量投影浮点）。
         */
        public SearchOutcome searchHybrid(String query, int topK, double threshold) {
            long wallStart = System.nanoTime();
            try {
                String body = mapper.writeValueAsString(java.util.Map.of(
                        "query", query, "topK", topK, "threshold", threshold));
                JsonNode data = post("/api/v1/hybrid/retrieval/search", body, retrievalTimeoutMs);
                if (data == null) {
                    return SearchOutcome.failed("hybrid 检索接口返回非 200 业务码", "RETRIEVAL_BACKEND_FAIL");
                }
                List<Hit> hits = parseMatches(data.path("matches"), true);
                double embedMs = data.path("queryEmbeddingMs").asDouble(0);
                Double searchMs = data.hasNonNull("vectorSearchMs") ? data.get("vectorSearchMs").asDouble() : null;
                double totalMs = data.path("retrievalTotalMs").asDouble(ms(System.nanoTime() - wallStart));
                return new SearchOutcome(hits.isEmpty() ? "EMPTY" : "SUCCESS", hits, embedMs,
                        searchMs, null, totalMs, null, null, data);
            } catch (HttpTimeoutException ex) {
                return new SearchOutcome("TIMEOUT", List.of(), 0, null, null,
                        ms(System.nanoTime() - wallStart), "hybrid 检索超时", "TIMEOUT_RETRIEVAL", null);
            } catch (Exception ex) {
                String msg = String.valueOf(ex.getMessage());
                String code = msg.contains("向量") ? "RETRIEVAL_EMBED_FAIL" : "RETRIEVAL_BACKEND_FAIL";
                return SearchOutcome.failed("hybrid 检索执行异常: " + ex.getClass().getSimpleName(), code);
            }
        }

        /** matches 解析；hybrid=true 时相似度槽位取 score（RRF 语义），否则取 similarity。 */
        private List<Hit> parseMatches(JsonNode matches, boolean hybrid) {
            List<Hit> hits = new ArrayList<>();
            int rank = 0;
            for (JsonNode m : matches) {
                rank++;
                String content = m.path("content").asText(null);
                String documentId = m.path("documentId").asText(null);
                Corpus.Chunk mapped = content == null ? null : corpus.mapHit(documentId, content);
                Double sim = m.hasNonNull(hybrid ? "score" : "similarity")
                        ? m.get(hybrid ? "score" : "similarity").asDouble() : null;
                Double dist = m.hasNonNull("distance") ? m.get("distance").asDouble() : null;
                Integer page = m.hasNonNull("page") ? m.get("page").asInt() : null;
                hits.add(new Hit(rank, m.path("chunkId").asText(null),
                        mapped == null ? null : mapped.logicalChunkId(), documentId,
                        m.path("title").asText(null), content, sim, dist, page, mapped == null));
            }
            return hits;
        }

        public AskOutcome askVector(String question, int topK) {
            return ask(question, topK, "/api/v1/rag/ask", null);
        }

        public AskOutcome askHybrid(String question, int topK) {
            return ask(question, topK, "/api/v1/hybrid/rag/ask", STRATEGY_HYBRID_RRF);
        }

        /** 问答（决策路径）；离线 mock 下生成类为 NOT_CONFIGURED，不调模型。 */
        private AskOutcome ask(String question, int topK, String path, String expectStrategy) {
            long wallStart = System.nanoTime();
            try {
                String body = mapper.writeValueAsString(java.util.Map.of("question", question, "topK", topK));
                JsonNode data = post(path, body, e2eTimeoutMs);
                if (data == null) {
                    return failedAsk("问答接口返回非 200 业务码", wallStart);
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
                        null,
                        data.path("retrievalStrategy").asText(expectStrategy));
            } catch (HttpTimeoutException ex) {
                AskOutcome base = failedAsk("端到端超时", wallStart);
                return new AskOutcome("TIMEOUT", null, null, null, null, null, List.of(),
                        0, 0, false, null, List.of(), 0, List.of(), List.of(),
                        null, null, null, base.totalMs(), "端到端超时", expectStrategy);
            } catch (Exception ex) {
                AskOutcome base = failedAsk("问答执行异常: " + ex.getClass().getSimpleName(), wallStart);
                return new AskOutcome("FAILED", null, null, null, null, null, List.of(),
                        0, 0, false, null, List.of(), 0, List.of(), List.of(),
                        null, null, null, base.totalMs(), base.errorReason(), expectStrategy);
            }
        }

        private AskOutcome failedAsk(String reason, long wallStart) {
            return new AskOutcome("FAILED", null, null, null, null, null, List.of(),
                    0, 0, false, null, List.of(), 0, List.of(), List.of(),
                    null, null, null, Math.round(ms(System.nanoTime() - wallStart)), reason, null);
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

    /** 稳定排序（供纯排序语义夹具；HTTP 后端保持服务端顺序，不重排）。 */
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
