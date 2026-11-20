package d37eval;

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
 * 检索后端抽象与两个实现：PGVECTOR（经应用 HTTP 链路）与 IN_MEMORY_LOCAL（内存降级）。
 *
 * <p>硬口径：内存后端耗时字段为 inMemorySearchMs，禁止写入 vectorSearchMs；
 * 两后端结果均带 retrievalBackend 标记，禁止混算。</p>
 */
public final class Backends {

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
                             String errorReason) {
    }

    public interface RetrievalBackend {
        String name();

        SearchOutcome search(String query, int topK, double threshold);
    }

    // ------------------------------------------------------------------
    // IN_MEMORY_LOCAL：冻结语料 + 真实向量余弦 + 稳定次级排序
    // ------------------------------------------------------------------
    public static final class InMemoryBackend implements RetrievalBackend {
        private final Corpus corpus;
        private final List<float[]> vectors;

        public InMemoryBackend(Corpus corpus) {
            this.corpus = corpus;
            List<float[]> vs = new ArrayList<>();
            for (Corpus.Chunk c : corpus.chunks()) {
                float[] v = LocalEmbedding.embed(c.content());
                if (LocalEmbedding.isZero(v)) {
                    throw new IllegalStateException("语料片段向量为零向量: " + c.logicalChunkId());
                }
                vs.add(v);
            }
            this.vectors = List.copyOf(vs);
        }

        @Override
        public String name() {
            return EnvProbe.BACKEND_IN_MEMORY;
        }

        @Override
        public SearchOutcome search(String query, int topK, double threshold) {
            long totalStart = System.nanoTime();
            long embedStart = System.nanoTime();
            float[] qv = LocalEmbedding.embed(query);
            double embedMs = ms(System.nanoTime() - embedStart);
            if (LocalEmbedding.isZero(qv)) {
                return new SearchOutcome("FAILED", List.of(), embedMs, null, null,
                        ms(System.nanoTime() - totalStart),
                        "查询向量为零向量，无法检索", "RETRIEVAL_EMBED_FAIL");
            }
            long searchStart = System.nanoTime();
            List<Hit> all = new ArrayList<>();
            for (int i = 0; i < corpus.chunks().size(); i++) {
                Corpus.Chunk c = corpus.chunks().get(i);
                double sim = LocalEmbedding.cosine(qv, vectors.get(i));
                if (sim >= threshold) {
                    all.add(new Hit(0, c.logicalChunkId(), c.logicalChunkId(), c.documentId(),
                            c.title(), c.content(), sim, 1.0 - sim, c.page(), false));
                }
            }
            sortHits(all);
            List<Hit> top = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, all.size()); i++) {
                Hit h = all.get(i);
                top.add(new Hit(i + 1, h.vectorChunkId(), h.logicalChunkId(), h.documentId(),
                        h.title(), h.content(), h.similarity(), h.distance(), h.page(), false));
            }
            double searchMs = ms(System.nanoTime() - searchStart);
            return new SearchOutcome(top.isEmpty() ? "EMPTY" : "SUCCESS", top, embedMs,
                    null, searchMs, ms(System.nanoTime() - totalStart), null, null);
        }
    }

    // ------------------------------------------------------------------
    // PGVECTOR：经应用真实 HTTP 链路（/retrieval/search 与 /rag/ask）
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
            return EnvProbe.BACKEND_PGVECTOR;
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

        @Override
        public SearchOutcome search(String query, int topK, double threshold) {
            long wallStart = System.nanoTime();
            try {
                String body = mapper.writeValueAsString(java.util.Map.of(
                        "query", query, "topK", topK, "threshold", threshold));
                JsonNode data = post("/api/v1/retrieval/search", body, retrievalTimeoutMs);
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

        /** RAG 问答（决策路径）；离线 mock 下生成类为 NOT_CONFIGURED，不调模型。 */
        public AskOutcome ask(String question, int topK) {
            long wallStart = System.nanoTime();
            try {
                String body = mapper.writeValueAsString(java.util.Map.of("question", question, "topK", topK));
                JsonNode data = post("/api/v1/rag/ask", body, e2eTimeoutMs);
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

    /** 稳定排序：相似度降序；同分以稳定逻辑 chunkId 升序作次级键（跨进程一致，C16）。 */
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
