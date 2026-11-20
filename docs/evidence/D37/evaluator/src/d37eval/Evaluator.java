package d37eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d37.rag.citation.UrlSafetyPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * D37 RAG 评测器主运行器。
 *
 * <p>用法：</p>
 * <pre>
 * java d37eval.Evaluator --config evaluator-config.json --repo <repoRoot> --out <runsRoot> [--rounds 3] [--warmup 3]
 * java d37eval.Evaluator --diff <runDirA> <runDirB> --out <diffReportFile>
 * </pre>
 *
 * <p>纪律：固定题序、单线程串行、预热不计、正式≥3 轮、原始记录全量落盘、
 * 指标一律由原始记录复算、重复运行独立目录、质量类字段轮间必须一致。</p>
 */
public final class Evaluator {

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        if (a.containsKey("diff")) {
            System.exit(diff(Path.of(a.get("diffA")), Path.of(a.get("diffB")),
                    Path.of(a.getOrDefault("out", "diff-report.txt"))));
        }
        Path repo = Path.of(a.get("repo"));
        Path configPath = Path.of(a.get("config"));
        JsonNode cfg = M.readTree(configPath.toFile());
        int rounds = Integer.parseInt(a.getOrDefault("rounds", cfg.path("rounds").asText("3")));
        int warmup = Integer.parseInt(a.getOrDefault("warmup", cfg.path("warmup").asText("3")));
        if (rounds < 3) {
            System.err.println("正式轮次必须 >= 3");
            System.exit(1);
        }
        String baseUrl = a.getOrDefault("baseUrl", cfg.path("baseUrl").asText("http://127.0.0.1:8080"));
        int topK = cfg.path("topK").asInt(5);
        double threshold = cfg.path("threshold").asDouble(0.0);
        int retrievalTimeoutMs = cfg.path("retrievalTimeoutMs").asInt(30000);
        int e2eTimeoutMs = cfg.path("e2eTimeoutMs").asInt(60000);
        String pgHost = cfg.path("pgHost").asText("192.168.200.153");
        int pgPort = cfg.path("pgPort").asInt(5432);

        Path datasetPath = repo.resolve(cfg.path("dataset").asText());
        Path corpusPath = repo.resolve(cfg.path("corpus").asText());
        Path manifestPath = repo.resolve(cfg.path("manifest").asText());

        // 1) 冻结校验（C05）：manifest 哈希复算，篡改即终止
        verifyManifest(repo, manifestPath);
        System.out.println("[OK] manifest 哈希复验通过");

        // 2) 数据集与语料加载（C04）
        Dataset dataset = Dataset.loadAndValidate(datasetPath, M);
        Corpus corpus = Corpus.load(corpusPath, M);
        System.out.println("[OK] 数据集 20 问四场景校验通过；语料 " + corpus.chunks().size() + " 片段");

        // 3) 环境探测（C01/C02/C03/C17）
        boolean keyPresent = notBlank(System.getenv("AI_API_KEY"));
        boolean modeDeepseek = "deepseek".equalsIgnoreCase(System.getenv().getOrDefault("AI_MODE", "mock"));
        boolean pgReachable = EnvProbe.tcpReachable(pgHost, pgPort, 2000);
        Backends.HttpBackend http = new Backends.HttpBackend(baseUrl, corpus, M, retrievalTimeoutMs, e2eTimeoutMs);
        boolean appUp = http.appUp();
        boolean appRetrievalOk = false;
        if (appUp) {
            Backends.SearchOutcome probe = http.search("数控机床主轴过热报警", 1, 0.0);
            appRetrievalOk = probe.status().equals("SUCCESS") || probe.status().equals("EMPTY");
        }
        boolean embeddingOk = EnvProbe.localEmbeddingOk();
        boolean chatModelPresent = appUp && modeDeepseek && keyPresent; // 配置级推断，见 env-probe 注记
        EnvProbe.ProbeInput input = new EnvProbe.ProbeInput(keyPresent, modeDeepseek, pgReachable,
                appUp, appRetrievalOk, chatModelPresent, embeddingOk, true);
        EnvProbe.ProbeResult probe = EnvProbe.classify(input);
        System.out.println("[PROBE] mode=" + probe.mode() + " backend=" + probe.backend()
                + " reason=" + probe.degradeReason());

        // 4) 独立运行目录（幂等，不覆盖）
        String stamp = OffsetDateTime.now().toString().replace(":", "").replace(".", "-");
        Path runDir = Path.of(a.get("out")).resolve("run-" + stamp + "-" + probe.backend());
        Files.createDirectories(runDir);
        EnvProbe.write(runDir.resolve("env-probe.json"),
                EnvProbe.toJson(input, probe, M, pgHost, pgPort, baseUrl), M);

        boolean blocked = probe.backend().equals(EnvProbe.BACKEND_BLOCKED);
        boolean httpMode = probe.backend().equals(EnvProbe.BACKEND_PGVECTOR);
        Backends.RetrievalBackend backend = httpMode ? http : new Backends.InMemoryBackend(corpus);

        // 5) 预热（不计入任何记录）
        if (!blocked) {
            for (int w = 0; w < warmup; w++) {
                for (Dataset.Question q : dataset.questions()) {
                    backend.search(q.question(), topK, threshold);
                    if (httpMode) {
                        http.ask(q.question(), topK);
                    }
                }
            }
            System.out.println("[OK] 预热 " + warmup + " 轮完成（不计入结果）");
        }

        // 6) 正式轮次：固定题序、单线程
        List<ObjectNode> rawRecords = new ArrayList<>();
        Map<String, List<String>> qualityByQuestion = new LinkedHashMap<>();
        Map<String, ObjectNode> aggregated = new LinkedHashMap<>();
        List<String> diverged = new ArrayList<>();
        Map<String, String> uuidMapping = new TreeMap<>();

        for (int round = 1; round <= rounds; round++) {
            for (Dataset.Question q : dataset.questions()) {
                ObjectNode rec = evaluateOne(q, round, backend, httpMode, blocked, http,
                        corpus, topK, threshold, probe.backend());
                rawRecords.add(rec);
                String quality = qualityProjection(rec);
                qualityByQuestion.computeIfAbsent(q.questionId(), k -> new ArrayList<>()).add(quality);
                if (round == rounds) {
                    aggregated.put(q.questionId(), rec);
                }
                for (JsonNode h : rec.path("hits")) {
                    if (h.hasNonNull("vectorChunkId") && h.hasNonNull("logicalChunkId")) {
                        uuidMapping.put(h.get("vectorChunkId").asText(), h.get("logicalChunkId").asText());
                    }
                }
            }
            System.out.println("[OK] 正式第 " + round + "/" + rounds + " 轮完成");
        }

        // 7) 轮间一致性（质量类字段逐字节一致）
        for (var e : qualityByQuestion.entrySet()) {
            String first = e.getValue().get(0);
            for (String other : e.getValue()) {
                if (!first.equals(other)) {
                    diverged.add(e.getKey());
                    break;
                }
            }
        }

        // 8) 落盘原始记录与映射
        try (var writer = Files.newBufferedWriter(runDir.resolve("raw-results.jsonl"), StandardCharsets.UTF_8)) {
            for (ObjectNode rec : rawRecords) {
                writer.write(M.copy().disable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(rec));
                writer.newLine();
            }
        }
        M.writeValue(runDir.resolve("chunk-id-mapping.json").toFile(), uuidMapping);

        // 9) 复算聚合指标 + baseline.csv + summary.md
        ObjectNode metrics = aggregate(dataset, aggregated, probe);
        M.writeValue(runDir.resolve("evaluation-metrics.json").toFile(), metrics);
        writeBaselineCsv(runDir.resolve("baseline.csv"), dataset, aggregated, probe.backend());
        int exitCode = writeSummary(runDir, dataset, aggregated, metrics, probe, rounds, warmup,
                diverged, rawRecords.size(), topK, threshold);

        if (!diverged.isEmpty()) {
            System.err.println("[FATAL] 轮间质量字段不一致（REPEAT_RESULT_DIVERGED）: " + diverged);
            System.err.println("本次运行不得写入基线。");
            System.exit(1);
        }
        System.out.println("[DONE] runDir=" + runDir + " exitCode=" + exitCode);
        System.exit(exitCode);
    }

    // ------------------------------------------------------------------
    // 单题单轮评测
    // ------------------------------------------------------------------
    private static ObjectNode evaluateOne(Dataset.Question q, int round,
                                          Backends.RetrievalBackend backend, boolean httpMode,
                                          boolean blocked, Backends.HttpBackend http, Corpus corpus,
                                          int topK, double threshold, String backendName) {
        ObjectNode rec = M.createObjectNode();
        rec.put("questionId", q.questionId());
        rec.put("scenario", q.scenario());
        rec.put("round", round);
        rec.put("retrievalBackend", backendName);
        rec.put("question", q.question());
        rec.put("expectedDecision", q.expectedDecision());

        Backends.SearchOutcome search = blocked
                ? new Backends.SearchOutcome("BLOCKED", List.of(), 0, null, null, 0, "后端 BLOCKED", null)
                : backend.search(q.question(), topK, threshold);
        Backends.AskOutcome ask = null;
        if (httpMode && !blocked) {
            ask = http.ask(q.question(), topK);
        }

        // 命中与相关性（文档级相关判定；QU 的 mustNot 全集使所有命中均不相关）
        List<String> returnedDocs = new ArrayList<>();
        List<String> returnedLogical = new ArrayList<>();
        boolean mappingAmbiguous = false;
        ArrayNode hitsNode = rec.putArray("hits");
        int relevant = 0;
        boolean judgeable = !q.expectedDocumentIds().isEmpty() || !q.mustNotReturnDocumentIds().isEmpty();
        for (Backends.Hit h : search.hits()) {
            returnedDocs.add(h.documentId());
            returnedLogical.add(h.logicalChunkId() == null ? "UNMAPPED:" + h.vectorChunkId() : h.logicalChunkId());
            mappingAmbiguous |= h.mappingAmbiguous();
            boolean rel = !q.expectedDocumentIds().isEmpty()
                    && q.expectedDocumentIds().contains(h.documentId());
            if (rel) {
                relevant++;
            }
            ObjectNode hn = hitsNode.addObject();
            hn.put("rank", h.rank());
            hn.put("vectorChunkId", h.vectorChunkId());
            hn.put("logicalChunkId", h.logicalChunkId());
            hn.put("documentId", h.documentId());
            hn.put("similarity", h.similarity());
            hn.put("distance", h.distance());
            hn.put("page", h.page());
            hn.put("relevant", rel);
            hn.put("mappingAmbiguous", h.mappingAmbiguous());
        }
        boolean docHit = MetricsCalculator.docHit(returnedDocs, q.expectedDocumentIds());
        boolean top1InExpected = !search.hits().isEmpty()
                && q.expectedDocumentIds().contains(search.hits().get(0).documentId());
        boolean allChunksCovered = q.expectedChunkIds().isEmpty()
                || returnedLogical.containsAll(q.expectedChunkIds());
        Double recall = MetricsCalculator.singleRecall(returnedLogical, q.expectedChunkIds(),
                returnedDocs, q.expectedDocumentIds());
        Double precision = judgeable ? MetricsCalculator.precision(relevant, search.hits().size()) : null;

        // 术语覆盖（C08）：期望术语必须出现在真实返回片段内容中
        StringBuilder returnedContent = new StringBuilder();
        search.hits().forEach(h -> returnedContent.append(h.content() == null ? "" : h.content()));
        List<String> missingTerms = new ArrayList<>();
        for (String term : q.expectedTerms()) {
            if (returnedContent.indexOf(term) < 0) {
                missingTerms.add(term);
            }
        }

        // 决策与引用静态校验（仅 HTTP 模式可判）
        String actualDecision = ask == null ? null : ask.decision();
        boolean decisionNotRun = ask == null || ask.status().equals("FAILED") || ask.status().equals("TIMEOUT");
        List<String> citationIssues = new ArrayList<>();
        boolean metadataRequiredMissing = false;
        boolean pageMissing = false;
        boolean mustNotViolated = false;
        if (ask != null && ask.status().equals("SUCCESS")) {
            for (Backends.SourceView s : ask.sources()) {
                if (s.documentId() != null && q.mustNotReturnDocumentIds().contains(s.documentId())) {
                    mustNotViolated = true;
                }
                if (s.sourceId() == null || !s.sourceId().equals(s.documentId())) {
                    addOnce(citationIssues, "CITATION_OWNERSHIP_MISMATCH");
                }
                if (s.citationId() == null || !s.citationId().equals(s.sourceRef())
                        || !s.citationId().matches("S\\d+")) {
                    addOnce(citationIssues, "CITATION_UNKNOWN_ID");
                }
                if (s.quotedSnippet() == null || !snippetSupported(corpus, s)) {
                    addOnce(citationIssues, "CITATION_UNSUPPORTED");
                }
                if (s.url() != null && UrlSafetyPolicy.sanitize(s.url()) == null) {
                    addOnce(citationIssues, "CITATION_FABRICATED_URL");
                }
                if (s.sourceId() == null || s.citationId() == null || s.sourceRef() == null
                        || s.chunkId() == null || s.documentId() == null || s.title() == null
                        || s.quotedSnippet() == null || s.similarity() == null || s.distance() == null) {
                    metadataRequiredMissing = true;
                }
                if (s.page() == null) {
                    pageMissing = true;
                }
            }
            if (ask.unknownCitationDetected()) {
                addOnce(citationIssues, "CITATION_UNKNOWN_ID");
            }
            // 注入执行迹象：回答回显不可信证据边界标记或系统提示泄露特征
            if (q.notes().contains("注入用例") && ask.answer() != null
                    && (ask.answer().contains("UNTRUSTED_EVIDENCE")
                    || ask.answer().contains("你是一个") && ask.answer().contains("系统提示"))) {
                addOnce(citationIssues, "CITATION_UNSUPPORTED");
                rec.put("injectionExecutionSuspected", true);
            }
        }

        // 标签判定
        FailureLabels.Input li = new FailureLabels.Input(
                q.expectedDecision(), actualDecision,
                search.status().equals("BLOCKED") ? null : search.status(),
                ask == null ? null : ask.generationStatus(),
                ask == null ? null : ask.errorCode(),
                search.reasonCode(),
                search.hits().size(), relevant, judgeable,
                !q.expectedDocumentIds().isEmpty(), docHit,
                !q.expectedChunkIds().isEmpty(), allChunksCovered,
                top1InExpected, false, mappingAmbiguous,
                citationIssues, metadataRequiredMissing, pageMissing,
                search.status().equals("TIMEOUT"),
                ask != null && "FAILED_TIMEOUT".equals(ask.generationStatus()),
                ask != null && ask.status().equals("TIMEOUT"),
                mustNotViolated, decisionNotRun, blocked);
        FailureLabels.Result labels = FailureLabels.evaluate(li);

        // 质量字段
        rec.put("retrievalStatus", search.status());
        rec.put("hitCount", search.hits().size());
        rec.put("relevantCount", relevant);
        rec.put("relevanceJudgeable", judgeable);
        rec.put("docHit", docHit);
        rec.put("top1InExpected", top1InExpected);
        rec.put("allChunksCovered", allChunksCovered);
        rec.put("recall", recall);
        rec.put("precision", precision);
        rec.put("noise", precision == null ? null : 1.0 - precision);
        rec.put("actualDecision", actualDecision);
        rec.put("decisionNotRun", decisionNotRun);
        if (ask != null) {
            rec.put("generationStatus", ask.generationStatus());
            rec.put("errorCode", ask.errorCode());
            rec.put("retrievedCount", ask.retrievedCount());
            rec.put("evidenceCount", ask.evidenceCount());
            rec.put("answer", ask.answer());
            rec.put("clarificationQuestion", ask.clarificationQuestion());
            putArray(rec, "missingInformation", ask.missingInformation());
            rec.put("conflictGroupCount", ask.conflictGroupCount());
            putArray(rec, "rejectedEvidenceIds", ask.rejectedEvidenceIds());
            putArray(rec, "citationNotes", ask.citationNotes());
            rec.put("sourceCount", ask.sources().size());
        }
        putArray(rec, "missingTerms", missingTerms);
        putArray(rec, "citationIssueCodes", citationIssues);
        rec.put("mustNotViolated", mustNotViolated);
        rec.put("metadataRequiredMissing", metadataRequiredMissing);
        rec.put("pageMissing", pageMissing);
        putArray(rec, "labels", labels.labels());
        rec.put("status", labels.status());

        // 计时（不进质量对比键；内存段独立命名）
        ObjectNode timing = rec.putObject("timing");
        timing.put("queryEmbeddingMs", search.queryEmbeddingMs());
        if (search.vectorSearchMs() != null) {
            timing.put("vectorSearchMs", search.vectorSearchMs());
        }
        if (search.inMemorySearchMs() != null) {
            timing.put("inMemorySearchMs", search.inMemorySearchMs());
        }
        timing.put("retrievalTotalMs", search.retrievalTotalMs());
        if (ask != null) {
            timing.put("askRetrievalMs", ask.retrievalMs());
            timing.put("assemblyMs", ask.assemblyMs());
            timing.put("generationMs", ask.generationMs());
            timing.put("askTotalMs", ask.totalMs());
        }
        rec.put("recordedAt", OffsetDateTime.now().toString());
        return rec;
    }

    /** quotedSnippet 必须是真实冻结语料内容的子串（按 documentId 归属核验）。 */
    private static boolean snippetSupported(Corpus corpus, Backends.SourceView s) {
        for (Corpus.Chunk c : corpus.chunks()) {
            if (c.documentId().equals(s.documentId()) && c.content().contains(s.quotedSnippet())) {
                return true;
            }
        }
        return false;
    }

    /** 质量投影：确定性字符串，排除耗时/时间戳/相似度浮点，用于轮间与跨运行 diff。 */
    private static String qualityProjection(ObjectNode rec) {
        StringBuilder sb = new StringBuilder();
        sb.append(rec.path("questionId").asText()).append('|');
        sb.append(rec.path("retrievalBackend").asText()).append('|');
        sb.append(rec.path("retrievalStatus").asText()).append('|');
        for (JsonNode h : rec.path("hits")) {
            sb.append(h.path("logicalChunkId").asText("null")).append(',')
                    .append(h.path("documentId").asText("null")).append(',')
                    .append(h.path("relevant").asBoolean()).append(';');
        }
        sb.append('|').append(rec.path("hitCount").asInt()).append('|')
                .append(rec.path("relevantCount").asInt()).append('|')
                .append(rec.path("docHit").asBoolean()).append('|')
                .append(rec.path("allChunksCovered").asBoolean()).append('|')
                .append(rec.path("actualDecision").asText("null")).append('|')
                .append(rec.path("generationStatus").asText("null")).append('|')
                .append(rec.path("errorCode").asText("null")).append('|')
                .append(rec.path("mustNotViolated").asBoolean()).append('|')
                .append(rec.path("metadataRequiredMissing").asBoolean()).append('|')
                .append(rec.path("pageMissing").asBoolean()).append('|');
        for (JsonNode t : rec.path("missingTerms")) {
            sb.append(t.asText()).append(',');
        }
        sb.append('|');
        for (JsonNode c : rec.path("citationIssueCodes")) {
            sb.append(c.asText()).append(',');
        }
        sb.append('|');
        for (JsonNode l : rec.path("labels")) {
            sb.append(l.asText()).append(',');
        }
        sb.append('|').append(rec.path("status").asText());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 聚合（一律由原始记录复算）
    // ------------------------------------------------------------------
    private static ObjectNode aggregate(Dataset dataset, Map<String, ObjectNode> agg,
                                        EnvProbe.ProbeResult probe) {
        ObjectNode root = M.createObjectNode();
        root.put("retrievalBackend", probe.backend());
        root.put("mode", probe.mode());
        ObjectNode overall = root.putObject("overall");
        List<Boolean> docHits = new ArrayList<>();
        List<Double> recalls = new ArrayList<>();
        List<Double> precisions = new ArrayList<>();
        int refuseExpected = 0, refuseCorrect = 0, answerExpected = 0, falseRefusal = 0;
        int unanswerable = 0, falseAnswer = 0, clarifyExpected = 0, clarifyCorrect = 0;
        int decisionsJudged = 0;
        int withSources = 0, citationBad = 0, sourcesTotal = 0, sourcesComplete = 0, pageMissingSources = 0;
        Map<String, Integer> labelDist = new TreeMap<>();
        Map<String, Integer> statusDist = new TreeMap<>();
        List<Double> embedTimes = new ArrayList<>();
        List<Double> searchTimes = new ArrayList<>();
        List<Double> totalTimes = new ArrayList<>();

        for (Dataset.Question q : dataset.questions()) {
            ObjectNode r = agg.get(q.questionId());
            String status = r.path("status").asText();
            statusDist.merge(status, 1, Integer::sum);
            for (JsonNode l : r.path("labels")) {
                labelDist.merge(l.asText(), 1, Integer::sum);
            }
            if (!q.expectedDocumentIds().isEmpty()) {
                docHits.add(r.path("docHit").asBoolean());
            }
            if (r.hasNonNull("recall")) {
                recalls.add(r.get("recall").asDouble());
            }
            if (r.hasNonNull("precision")) {
                precisions.add(r.get("precision").asDouble());
            }
            boolean decisionJudged = !r.path("decisionNotRun").asBoolean(true);
            if (decisionJudged) {
                decisionsJudged++;
            }
            String actual = r.path("actualDecision").asText(null);
            if ("REFUSE".equals(q.expectedDecision())) {
                refuseExpected++;
                unanswerable++;
                if (decisionJudged && "REFUSE".equals(actual)) {
                    refuseCorrect++;
                }
                if (decisionJudged && "ANSWER".equals(actual)) {
                    falseAnswer++;
                }
            }
            if ("ANSWER".equals(q.expectedDecision())) {
                answerExpected++;
                if (decisionJudged && "REFUSE".equals(actual)) {
                    falseRefusal++;
                }
            }
            if ("CLARIFY".equals(q.expectedDecision())) {
                clarifyExpected++;
                if (decisionJudged && "CLARIFY".equals(actual)) {
                    clarifyCorrect++;
                }
            }
            int sc = r.path("sourceCount").asInt(0);
            if (sc > 0) {
                withSources++;
                sourcesTotal += sc;
                if (!r.path("citationIssueCodes").isEmpty()) {
                    citationBad++;
                }
                if (!r.path("metadataRequiredMissing").asBoolean()) {
                    sourcesComplete += sc;
                }
                if (r.path("pageMissing").asBoolean()) {
                    pageMissingSources++;
                }
            }
            JsonNode t = r.path("timing");
            if (t.hasNonNull("queryEmbeddingMs")) {
                embedTimes.add(t.get("queryEmbeddingMs").asDouble());
            }
            if (t.hasNonNull("vectorSearchMs")) {
                searchTimes.add(t.get("vectorSearchMs").asDouble());
            } else if (t.hasNonNull("inMemorySearchMs")) {
                searchTimes.add(t.get("inMemorySearchMs").asDouble());
            }
            if (t.hasNonNull("retrievalTotalMs")) {
                totalTimes.add(t.get("retrievalTotalMs").asDouble());
            }
        }
        putMetric(overall, "hitRateAtK", MetricsCalculator.hitRate(docHits));
        putMetric(overall, "recallMacro", MetricsCalculator.macroAverage(recalls));
        putMetric(overall, "precisionMacro", MetricsCalculator.macroAverage(precisions));
        Double pMacro = MetricsCalculator.macroAverage(precisions);
        putMetric(overall, "noiseMacro", pMacro == null ? null : 1.0 - pMacro);
        // 决策类指标：无任何决策被实际执行（如离线内存后端）时为 N/A，不得显示 0 造成“决策全错”误读
        putMetric(overall, "refusalAccuracy", decisionsJudged == 0 ? null : MetricsCalculator.rate(refuseCorrect, refuseExpected));
        putMetric(overall, "falseRefusalRate", decisionsJudged == 0 ? null : MetricsCalculator.rate(falseRefusal, answerExpected));
        putMetric(overall, "falseAnswerRate", decisionsJudged == 0 ? null : MetricsCalculator.rate(falseAnswer, unanswerable));
        putMetric(overall, "clarifyAccuracy", decisionsJudged == 0 ? null : MetricsCalculator.rate(clarifyCorrect, clarifyExpected));
        putMetric(overall, "citationLegality", withSources == 0 ? null : 1.0 - (double) citationBad / withSources);
        putMetric(overall, "metadataCompleteness", MetricsCalculator.rate(sourcesComplete, sourcesTotal));
        overall.put("pageMissingQuestionCount", pageMissingSources);
        overall.put("denominators", String.format(
                "refuseExpected=%d answerExpected=%d unanswerable=%d clarifyExpected=%d withSources=%d sourcesTotal=%d decisionsJudged=%d",
                refuseExpected, answerExpected, unanswerable, clarifyExpected, withSources, sourcesTotal, decisionsJudged));
        ObjectNode statuses = overall.putObject("statusCounts");
        for (String s : List.of("PASS", "FAIL", "BLOCKED", "NOT_RUN")) {
            statuses.put(s, statusDist.getOrDefault(s, 0));
        }
        ObjectNode labels = overall.putObject("labelDistribution");
        labelDist.forEach(labels::put);
        ObjectNode timing = root.putObject("timingStats");
        timing.set("queryEmbeddingMs", timingNode(embedTimes));
        timing.set(probe.backend().equals(EnvProbe.BACKEND_PGVECTOR) ? "vectorSearchMs" : "inMemorySearchMs",
                timingNode(searchTimes));
        timing.set("retrievalTotalMs", timingNode(totalTimes));
        timing.put("note", "耗时只报分布，不进基线对比键；不含 SSE 传输/前端渲染/人工等待；"
                + "内存后端耗时为 inMemorySearchMs，非数据库耗时。");
        return root;
    }

    private static ObjectNode timingNode(List<Double> samples) {
        MetricsCalculator.TimingStats s = MetricsCalculator.timing(samples);
        ObjectNode n = M.createObjectNode();
        putMetric(n, "mean", s.mean());
        putMetric(n, "p50", s.p50());
        putMetric(n, "p95", s.p95());
        putMetric(n, "min", s.min());
        putMetric(n, "max", s.max());
        n.put("count", s.count());
        return n;
    }

    private static void putMetric(ObjectNode n, String field, Double v) {
        if (v == null) {
            n.putNull(field);
        } else {
            n.put(field, v);
        }
    }

    private static void writeBaselineCsv(Path file, Dataset dataset, Map<String, ObjectNode> agg,
                                         String backend) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("questionId,scenario,retrievalBackend,expectedDecision,actualDecision,retrievalStatus,")
                .append("generationStatus,errorCode,hitCount,relevantCount,docHit,recall,precision,noise,")
                .append("returnedLogicalIds,labels,status\n");
        for (Dataset.Question q : dataset.questions()) {
            ObjectNode r = agg.get(q.questionId());
            List<String> ids = new ArrayList<>();
            r.path("hits").forEach(h -> ids.add(h.path("logicalChunkId").asText("null")));
            List<String> labels = new ArrayList<>();
            r.path("labels").forEach(l -> labels.add(l.asText()));
            sb.append(csv(q.questionId())).append(',').append(csv(q.scenario())).append(',')
                    .append(csv(backend)).append(',').append(csv(q.expectedDecision())).append(',')
                    .append(csv(r.path("actualDecision").asText("NOT_RUN"))).append(',')
                    .append(csv(r.path("retrievalStatus").asText())).append(',')
                    .append(csv(r.path("generationStatus").asText("NOT_RUN"))).append(',')
                    .append(csv(r.path("errorCode").asText(""))).append(',')
                    .append(r.path("hitCount").asInt()).append(',').append(r.path("relevantCount").asInt()).append(',')
                    .append(r.path("docHit").asBoolean()).append(',')
                    .append(MetricsCalculator.fmt(r.hasNonNull("recall") ? r.get("recall").asDouble() : null)).append(',')
                    .append(MetricsCalculator.fmt(r.hasNonNull("precision") ? r.get("precision").asDouble() : null)).append(',')
                    .append(MetricsCalculator.fmt(r.hasNonNull("noise") ? r.get("noise").asDouble() : null)).append(',')
                    .append(csv(String.join(";", ids))).append(',')
                    .append(csv(String.join(";", labels))).append(',')
                    .append(csv(r.path("status").asText())).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains(";")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static int writeSummary(Path runDir, Dataset dataset, Map<String, ObjectNode> agg,
                                    ObjectNode metrics, EnvProbe.ProbeResult probe, int rounds,
                                    int warmup, List<String> diverged, int rawCount,
                                    int topK, double threshold) throws Exception {
        ObjectNode overall = (ObjectNode) metrics.path("overall");
        JsonNode sc = overall.path("statusCounts");
        int pass = sc.path("PASS").asInt();
        int fail = sc.path("FAIL").asInt();
        int blocked = sc.path("BLOCKED").asInt();
        int notRun = sc.path("NOT_RUN").asInt();
        StringBuilder sb = new StringBuilder();
        sb.append("# D37 评测汇总（由原始记录复算生成）\n\n");
        sb.append("- 生成时间：").append(OffsetDateTime.now()).append("\n");
        sb.append("- 模式：").append(probe.mode()).append("；检索后端：").append(probe.backend()).append("\n");
        sb.append("- 降级/判定原因：").append(probe.degradeReason()).append("\n");
        sb.append("- 受控参数（EVAL_CONTROLLED_BASELINE）：topK=").append(topK)
                .append("，threshold=").append(threshold).append("（显式传入，不依赖应用默认值）\n");
        sb.append("- 预热 ").append(warmup).append(" 轮（不计）；正式 ").append(rounds)
                .append(" 轮；原始记录 ").append(rawCount).append(" 条（每条带 retrievalBackend）\n");
        sb.append("- 轮间质量一致性：").append(diverged.isEmpty() ? "全部一致" : "不一致题目 " + diverged).append("\n\n");
        sb.append("## 四态计数（合计=").append(pass + fail + blocked + notRun).append("，应等于 20）\n\n");
        sb.append("| PASS | FAIL | BLOCKED | NOT RUN |\n|---|---|---|---|\n");
        sb.append("| ").append(pass).append(" | ").append(fail).append(" | ").append(blocked)
                .append(" | ").append(notRun).append(" |\n\n");
        sb.append("## 总体指标（分母 0 = N/A）\n\n| 指标 | 值 |\n|---|---|\n");
        for (String k : List.of("hitRateAtK", "recallMacro", "precisionMacro", "noiseMacro",
                "refusalAccuracy", "falseRefusalRate", "falseAnswerRate", "clarifyAccuracy",
                "citationLegality", "metadataCompleteness")) {
            JsonNode v = overall.path(k);
            sb.append("| ").append(k).append(" | ")
                    .append(v.isNumber() ? MetricsCalculator.fmt(v.asDouble()) : "N/A").append(" |\n");
        }
        sb.append("\n分母口径：").append(overall.path("denominators").asText()).append("\n");
        sb.append("\n## 标签分布\n\n");
        overall.path("labelDistribution").properties().forEach(e ->
                sb.append("- ").append(e.getKey()).append(" × ").append(e.getValue().asInt()).append("\n"));
        sb.append("\n## 逐题状态\n\n| 题号 | 场景 | 期望 | 实际 | 状态 | 标签 |\n|---|---|---|---|---|---|\n");
        for (Dataset.Question q : dataset.questions()) {
            ObjectNode r = agg.get(q.questionId());
            List<String> labels = new ArrayList<>();
            r.path("labels").forEach(l -> labels.add(l.asText()));
            sb.append("| ").append(q.questionId()).append(" | ").append(q.scenario()).append(" | ")
                    .append(q.expectedDecision()).append(" | ")
                    .append(r.path("actualDecision").asText("NOT_RUN")).append(" | ")
                    .append(r.path("status").asText()).append(" | ")
                    .append(String.join("; ", labels)).append(" |\n");
        }
        sb.append("\n## 已知偏差\n\n");
        sb.append("- Embedding 为本地哈希词法模型（local-hashing-embedding/local-v1/384/COSINE），非神经语义模型；结论不得外推到神经 Embedding。\n");
        sb.append("- 语料仅 3 文档 8 片段、20 问，统计功效有限；单标签标注下噪声占比不等价人工全量相关性评估。\n");
        sb.append("- 跨后端（PGVECTOR vs IN_MEMORY_LOCAL）结果不可直接比较；内存耗时不是数据库耗时。\n");
        sb.append("- 离线模式下生成类指标 NOT RUN（mock 无 ChatModel，NOT_CONFIGURED 为受控态）。\n");
        Files.writeString(runDir.resolve("summary.md"), sb.toString(), StandardCharsets.UTF_8);
        if (fail > 0) {
            return 1;
        }
        return pass > 0 ? 0 : 2;
    }

    // ------------------------------------------------------------------
    // manifest 复验 / diff
    // ------------------------------------------------------------------
    private static void verifyManifest(Path repo, Path manifestPath) throws Exception {
        JsonNode manifest = M.readTree(manifestPath.toFile());
        JsonNode files = manifest.path("files");
        if (!files.isObject() || files.isEmpty()) {
            throw new IllegalStateException("manifest 缺少 files 哈希清单");
        }
        for (var e : files.properties()) {
            Path f = repo.resolve(e.getKey());
            String actual = sha256(f);
            if (!actual.equalsIgnoreCase(e.getValue().asText())) {
                throw new IllegalStateException("manifest 哈希不一致（疑似篡改）: " + e.getKey());
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 二次运行一致性 diff：只比较质量投影，逐字节一致才返回 0。 */
    private static int diff(Path runA, Path runB, Path report) throws Exception {
        List<String> qa = qualityLines(runA.resolve("raw-results.jsonl"));
        List<String> qb = qualityLines(runB.resolve("raw-results.jsonl"));
        List<String> diffs = new ArrayList<>();
        if (qa.size() != qb.size()) {
            diffs.add("记录数不一致: " + qa.size() + " vs " + qb.size());
        } else {
            for (int i = 0; i < qa.size(); i++) {
                if (!qa.get(i).equals(qb.get(i))) {
                    diffs.add("记录 " + i + " 质量字段不一致");
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("runA=").append(runA).append("\nrunB=").append(runB).append("\n");
        sb.append("recordsA=").append(qa.size()).append(" recordsB=").append(qb.size()).append("\n");
        sb.append(diffs.isEmpty() ? "QUALITY_FIELDS_IDENTICAL\n" : "QUALITY_FIELDS_DIVERGED\n");
        diffs.forEach(d -> sb.append(d).append('\n'));
        Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
        System.out.println(diffs.isEmpty() ? "[DIFF] 质量字段逐字节一致" : "[DIFF] 存在不一致: " + diffs.size());
        return diffs.isEmpty() ? 0 : 1;
    }

    private static List<String> qualityLines(Path rawFile) throws Exception {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(rawFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            out.add(qualityProjection((ObjectNode) M.readTree(line)));
        }
        return out;
    }

    private static void addOnce(List<String> list, String code) {
        if (!list.contains(code)) {
            list.add(code);
        }
    }

    private static void putArray(ObjectNode n, String field, List<String> values) {
        ArrayNode arr = n.putArray(field);
        values.forEach(arr::add);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--diff" -> {
                    a.put("diff", "true");
                    a.put("diffA", args[++i]);
                    a.put("diffB", args[++i]);
                }
                case "--config", "--repo", "--out", "--rounds", "--warmup", "--baseUrl" ->
                        a.put(args[i].substring(2), args[++i]);
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        return a;
    }

    private Evaluator() {
    }
}
