package com.zl.d41.hybrid.keyword;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d41.hybrid.config.HybridProperties;
import com.zl.d41.hybrid.keyword.KeywordFilterCriteria;
import com.zl.d41.hybrid.keyword.KeywordIndexStats;
import com.zl.d41.hybrid.keyword.KeywordTokenizer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 内存倒排索引（keywordRoute=IN_PROCESS_INVERTED，KP1 裁决）：
 * 启动时从 PG 向量表<b>只读</b>加载全部 chunk（零写入、零 DDL），构建 term → posting 倒排。
 *
 * <p>确定性构建（任务书 §3.3 / C03）：term 字典序、posting 按 chunkId 升序、
 * 规范序列化摘要 {@code indexDigest}；两次构建摘要必须一致。</p>
 *
 * <p>语料一致性（KP2 / C03）：构建期复算规范指纹，与冻结值不一致立即
 * {@code CORPUS_DRIFT} fail-fast，禁止用漂移语料建倒排。</p>
 *
 * <p>只读语义：构建后快照不可变；语料变更须显式调用 {@link #rebuild()}
 * （由 {@code POST /api/v1/hybrid/keyword-index/rebuild} 端点暴露）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class InvertedIndex {

    /** 单个 posting：chunkId + 词频。 */
    public record Posting(String chunkId, int tf) {
    }

    /** 索引侧 chunk 显式字段（缺字段容忍为 null，不补造——任务书 §3.7）。 */
    public record ChunkEntry(String chunkId, String content, String contentSha256, int contentChars,
                             String documentId, String title, Integer version, String strategy,
                             Integer chunkIndex, String splitVersion, int documentLength,
                             String tenantId, String deptId, String deviceId) {

        public KeywordFilterCriteria.ChunkFields filterFields() {
            return new KeywordFilterCriteria.ChunkFields(documentId, title, version, strategy,
                    tenantId, deptId, deviceId);
        }
    }

    /** 不可变快照。 */
    private static final class Snapshot {
        final Map<String, ChunkEntry> chunksById;                 // LinkedHashMap，chunkId 升序
        final SortedMap<String, List<Posting>> postings;          // term 字典序 → posting（chunkId 升序）
        final Map<String, Integer> docLength;                     // chunkId → Σtf
        final double avgDocLength;
        final String collectionFingerprint;
        final String indexDigest;
        final long indexUtf8Bytes;
        final KeywordIndexStats stats;

        Snapshot(Map<String, ChunkEntry> chunksById, SortedMap<String, List<Posting>> postings,
                 Map<String, Integer> docLength, double avgDocLength, String collectionFingerprint,
                 String indexDigest, long indexUtf8Bytes, KeywordIndexStats stats) {
            this.chunksById = chunksById;
            this.postings = postings;
            this.docLength = docLength;
            this.avgDocLength = avgDocLength;
            this.collectionFingerprint = collectionFingerprint;
            this.indexDigest = indexDigest;
            this.indexUtf8Bytes = indexUtf8Bytes;
            this.stats = stats;
        }
    }

    private final JdbcTemplate pgJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final KeywordTokenizer tokenizer;
    private final HybridProperties properties;

    private volatile Snapshot snapshot;

    public InvertedIndex(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate pgJdbcTemplate,
                         @Qualifier("jacksonObjectMapper") ObjectMapper objectMapper,
                         @Qualifier("keywordTokenizer") KeywordTokenizer tokenizer,
                         @Qualifier("hybridProperties") HybridProperties properties) {
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.objectMapper = objectMapper;
        this.tokenizer = tokenizer;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        this.snapshot = build();
    }

    /** 显式重建（语料变更后的人工动作；只重建内存索引，不触任何写入）。 */
    public synchronized KeywordIndexStats rebuild() {
        this.snapshot = build();
        return this.snapshot.stats;
    }

    public KeywordIndexStats stats() {
        return snapshot.stats;
    }

    public ChunkEntry chunk(String chunkId) {
        return snapshot.chunksById.get(chunkId);
    }

    public List<Posting> postings(String term) {
        return snapshot.postings.getOrDefault(term, List.of());
    }

    public int docLength(String chunkId) {
        return snapshot.docLength.getOrDefault(chunkId, 0);
    }

    public double avgDocLength() {
        return snapshot.avgDocLength;
    }

    public int chunkCount() {
        return snapshot.chunksById.size();
    }

    /** BM25 IDF：ln(1 + (N - df + 0.5) / (df + 0.5))（Robertson & Zaragoza 2009 标准式）。 */
    public double idf(String term) {
        return idf(chunkCount(), postings(term).size());
    }

    /** 纯函数口径（供 SelfTest 手算夹具直测，C01）：IDF = ln(1 + (N - df + 0.5)/(df + 0.5))。 */
    public static double idf(int totalDocs, int documentFrequency) {
        return Math.log(1.0d + (totalDocs - documentFrequency + 0.5d) / (documentFrequency + 0.5d));
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------
    private Snapshot build() {
        long heapBefore = heapUsedBytes();
        long start = System.nanoTime();

        String sql = "SELECT id::text, content, metadata::text FROM "
                + qualifiedTable() + " ORDER BY id::text";
        List<Map<String, Object>> rows = pgJdbcTemplate.queryForList(sql);

        Map<String, ChunkEntry> chunksById = new TreeMap<>();
        SortedMap<String, List<Posting>> postingsRaw = new TreeMap<>();
        Map<String, Integer> docLength = new TreeMap<>();
        List<String> canonicalLines = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) {
            String chunkId = String.valueOf(row.get("id"));
            String content = row.get("content") == null ? "" : String.valueOf(row.get("content"));
            JsonNode meta = parseMetadata(row.get("metadata") == null ? null
                    : String.valueOf(row.get("metadata")));

            String contentSha = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
            Integer version = meta.hasNonNull("version") && meta.get("version").isNumber()
                    ? meta.get("version").asInt() : null;
            Integer chunkIndex = meta.hasNonNull("chunkIndex") && meta.get("chunkIndex").isNumber()
                    ? meta.get("chunkIndex").asInt() : null;
            String documentId = textOrNull(meta, "documentId");
            String title = textOrNull(meta, "title");
            String strategy = textOrNull(meta, "strategy");
            String splitVersion = textOrNull(meta, "splitVersion");
            // P2：权限三键从 metadata 读取（缺键=null=遗留无标签，匹配端 fail-closed）。
            // 注意：canonical 指纹行<b>不含</b>权限键——既有行补写 metadata 不改变冻结指纹（H6 依据）。
            String tenantId = textOrNull(meta, "tenantId");
            String deptId = textOrNull(meta, "deptId");
            String deviceId = textOrNull(meta, "deviceId");

            List<String> features = tokenizer.extract(content);
            SortedMap<String, Integer> tf = new TreeMap<>();
            for (String term : features) {
                tf.merge(term, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                postingsRaw.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                        .add(new Posting(chunkId, e.getValue()));
            }
            docLength.put(chunkId, features.size());
            chunksById.put(chunkId, new ChunkEntry(chunkId, content, contentSha, content.length(),
                    documentId, title, version, strategy, chunkIndex, splitVersion,
                    features.size(), tenantId, deptId, deviceId));
            canonicalLines.add(String.join("|", chunkId, contentSha, String.valueOf(content.length()),
                    orNull(documentId), orNull(version), orNull(strategy), orNull(chunkIndex),
                    orNull(splitVersion)));
        }

        if (chunksById.isEmpty()) {
            throw new IllegalStateException("KEYWORD_INDEX_EMPTY: 向量表 " + qualifiedTable()
                    + " 无行，禁止在空语料上构建倒排（KP2 门禁）");
        }

        // 规范指纹（与 probe/canonical-fingerprint.json 同算法）
        Collections.sort(canonicalLines);
        String fingerprint = sha256Hex(
                String.join("\n", canonicalLines).getBytes(StandardCharsets.UTF_8));
        String expected = properties.getExpectedCollectionFingerprint();
        if (expected != null && !expected.isBlank() && !expected.trim().equalsIgnoreCase(fingerprint)) {
            throw new IllegalStateException("CORPUS_DRIFT: 期望指纹=" + expected.trim()
                    + " 实际=" + fingerprint + "，禁止用漂移语料建倒排（KP2/熔断#2）");
        }

        // posting 确定性排序（chunkId 升序）
        SortedMap<String, List<Posting>> postings = new TreeMap<>();
        long postingCount = 0;
        for (Map.Entry<String, List<Posting>> e : postingsRaw.entrySet()) {
            List<Posting> list = new ArrayList<>(e.getValue());
            list.sort((a, b) -> a.chunkId().compareTo(b.chunkId()));
            postings.put(e.getKey(), List.copyOf(list));
            postingCount += list.size();
        }

        double avg = docLength.values().stream().mapToInt(Integer::intValue).average().orElse(0.0d);

        // 规范序列化：digest + utf8 精确字节（postings 段）
        StringBuilder ser = new StringBuilder();
        for (Map.Entry<String, List<Posting>> e : postings.entrySet()) {
            ser.append(e.getKey()).append('=');
            List<Posting> ps = e.getValue();
            for (int i = 0; i < ps.size(); i++) {
                if (i > 0) {
                    ser.append(',');
                }
                ser.append(ps.get(i).chunkId()).append(':').append(ps.get(i).tf());
            }
            ser.append('\n');
        }
        long utf8Bytes = ser.toString().getBytes(StandardCharsets.UTF_8).length;
        StringBuilder digestSource = new StringBuilder(ser);
        for (Map.Entry<String, Integer> e : docLength.entrySet()) {
            digestSource.append("LEN ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        digestSource.append(String.format(Locale.ROOT, "AVG %.6f\n", avg));
        String digest = sha256Hex(digestSource.toString().getBytes(StandardCharsets.UTF_8));

        long buildMsRaw = System.nanoTime() - start;
        long heapAfter = heapUsedBytes();
        double buildMs = Math.round(buildMsRaw / 1_000.0d) / 1_000.0d;
        KeywordIndexStats stats = new KeywordIndexStats(chunksById.size(), postings.size(),
                (int) postingCount, avg, utf8Bytes, fingerprint, digest, buildMs,
                heapAfter - heapBefore,
                OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        log.info("hybrid 倒排构建完成: chunks={}, terms={}, postings={}, avgLen={}, utf8Bytes={}, "
                        + "fingerprint={}, digest={}, buildMs={}, heapDeltaBytes={}（粗测口径）",
                stats.chunkCount(), stats.termCount(), stats.postingCount(), stats.avgDocumentLength(),
                stats.indexUtf8Bytes(), stats.collectionFingerprint(), stats.indexDigest(),
                stats.buildMs(), stats.buildHeapDeltaBytes());

        return new Snapshot(Collections.unmodifiableMap(new LinkedHashMap<>(chunksById)),
                Collections.unmodifiableSortedMap(postings),
                Collections.unmodifiableMap(new LinkedHashMap<>(docLength)),
                avg, fingerprint, digest, utf8Bytes, stats);
    }

    private String qualifiedTable() {
        return properties.getSchema().trim() + "." + properties.getTable().trim();
    }

    private JsonNode parseMetadata(String json) {
        try {
            if (json == null || json.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            log.warn("hybrid 倒排 metadata 解析失败，按缺字段容忍处理: {}", ex.getClass().getSimpleName());
            return objectMapper.createObjectNode();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String orNull(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static long heapUsedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
