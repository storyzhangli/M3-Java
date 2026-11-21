package hybrideval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * D38-R02 对比器（P6）：全部数字由原始记录现算，禁止引用任何缓存指标。
 *
 * <p>输入：一次混合评测 run 目录（含 raw-results.jsonl，两策略 240 条），
 * 可选第二个 run 目录做 C11 跨运行一致性。输出 comparison-report.json、
 * metrics-matrix.csv、cases.md、decision.txt。</p>
 *
 * <p>决策规则（任务书 P6 冻结原文的机器化）：</p>
 * <pre>
 * 保留 = Δrecall ≥ 0pp ∧ Δprecision ≥ +5pp ∧ QU 误检数不增 ∧ Δt ≤ 100% ∧ 无新增引用/拒答 FAIL
 * 回退 = 精度提升 &lt; +1pp ∨ 召回净损失 ∨ QF-004 翻案（PURE 拒→HYBRID 答）
 * 两者皆不满足 → INCONCLUSIVE（如实报告，不强行给结论）
 * </pre>
 */
public final class Compare {

    private static final ObjectMapper M = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String PURE = Backends.STRATEGY_PURE_VECTOR;
    private static final String HYB = Backends.STRATEGY_HYBRID_RRF;

    static void run(Path runDir, Path runDirB, Path repo, Path outDir, Path configOrNull) throws Exception {
        Files.createDirectories(outDir);
        if (configOrNull == null) {
            throw new IllegalArgumentException("compare 模式必须提供 --config（数据集路径来源）");
        }
        JsonNode cfg = M.readTree(configOrNull.toFile());
        Dataset dataset = Dataset.loadAndValidate(repo.resolve(cfg.path("dataset").asText()), M);

        List<ObjectNode> recs = loadRaw(runDir);
        Map<String, List<String>> projByKey = new LinkedHashMap<>();
        Map<String, ObjectNode> last = new LinkedHashMap<>();
        Map<String, Integer> lastRound = new LinkedHashMap<>();
        for (ObjectNode r : recs) {
            String key = r.path("questionId").asText() + "|" + r.path("strategy").asText();
            projByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(Evaluator.qualityProjection(r));
            int round = r.path("round").asInt();
            if (lastRound.getOrDefault(key, -1) <= round) {
                last.put(key, r);
                lastRound.put(key, round);
            }
        }
        ObjectNode report = M.createObjectNode();
        report.put("generatedAt", OffsetDateTime.now().toString());
        report.put("runDir", runDir.toString());
        report.put("records", recs.size());
        if (recs.size() != dataset.questions().size() * lastRoundValues(lastRound) * 2) {
            report.put("recordCountWarning", "原始记录数 != 20 × rounds × 2");
        }

        // 运行内轮间确定性（C10 前置）
        List<String> intraDiverged = new ArrayList<>();
        for (var e : projByKey.entrySet()) {
            if (e.getValue().stream().distinct().count() > 1) {
                intraDiverged.add(e.getKey());
            }
        }
        report.set("intraRunDivergedKeys", toArray(intraDiverged));

        // C11 跨运行确定性（可选）
        boolean crossIdentical = true;
        List<String> crossDiverged = new ArrayList<>();
        if (runDirB != null) {
            Map<String, String> projB = new LinkedHashMap<>();
            for (ObjectNode r : loadRaw(runDirB)) {
                projB.put(r.path("questionId").asText() + "|" + r.path("strategy").asText(),
                        Evaluator.qualityProjection(r));
            }
            for (var e : projByKey.entrySet()) {
                String a = e.getValue().get(e.getValue().size() - 1);
                String b = projB.get(e.getKey());
                if (b == null || !a.equals(b)) {
                    crossDiverged.add(e.getKey());
                    crossIdentical = false;
                }
            }
            report.put("runDirB", runDirB.toString());
            report.set("crossRunDivergedKeys", toArray(crossDiverged));
        }
        boolean deterministic = intraDiverged.isEmpty() && crossIdentical;
        report.put("deterministic", deterministic);

        // 指标（由末轮记录复算，三轮投影一致故末轮即全体代表）
        ObjectNode mPure = Evaluator.aggregate(dataset, last, PURE);
        ObjectNode mHyb = Evaluator.aggregate(dataset, last, HYB);
        ObjectNode metrics = report.putObject("metrics");
        metrics.set(PURE, mPure);
        metrics.set(HYB, mHyb);

        Map<String, Double[]> deltas = new TreeMap<>();
        StringBuilder csv = new StringBuilder("metric,pure_vector,hybrid_rrf,delta_hyb_minus_pure,note\n");
        for (String k : List.of("hitRateAtK", "recallMacro", "precisionMacro", "mrrMacro", "noiseMacro",
                "refusalAccuracy", "falseRefusalRate", "falseAnswerRate", "clarifyAccuracy",
                "citationLegality", "metadataCompleteness")) {
            Double pv = num(mPure.path("overall"), k);
            Double hv = num(mHyb.path("overall"), k);
            Double d = (pv == null || hv == null) ? null : hv - pv;
            deltas.put(k, new Double[]{pv, hv});
            csv.append(k).append(',').append(fmt(pv)).append(',').append(fmt(hv)).append(',')
                    .append(d == null ? "N/A" : signed(d)).append(",宏平均；百分点=×100\n");
        }
        int pTop1 = mPure.path("overall").path("top1InExpectedCount").asInt();
        int hTop1 = mHyb.path("overall").path("top1InExpectedCount").asInt();
        csv.append("top1InExpectedCount,").append(pTop1).append(',').append(hTop1).append(',')
                .append(String.format(Locale.ROOT, "%+d", hTop1 - pTop1)).append(",计数（分母=有文档期望题数）\n");

        // 成本（检索总时长均值比值）
        double pTotal = mPure.path("timingStats").path("retrievalTotalMs").path("mean").asDouble(0);
        double hTotal = mHyb.path("timingStats").path("retrievalTotalMs").path("mean").asDouble(0);
        double timeRatio = pTotal > 0 ? hTotal / pTotal : Double.NaN;
        csv.append("retrievalTotalMsMean,").append(String.format(Locale.ROOT, "%.2f", pTotal))
                .append(',').append(String.format(Locale.ROOT, "%.2f", hTotal))
                .append(',').append(String.format(Locale.ROOT, "%.2fx", timeRatio))
                .append(",毫秒均值；纯本地 localhost\n");
        report.put("timeRatioHybOverPure", Double.isNaN(timeRatio) ? null : timeRatio);

        // QU 误检与 QF-004 翻案、新增引用/拒答 FAIL
        int pQuFalse = 0, hQuFalse = 0;
        List<String> newCitationRefusalFail = new ArrayList<>();
        String qf004Pure = null, qf004Hyb = null;
        for (Dataset.Question q : dataset.questions()) {
            ObjectNode p = last.get(q.questionId() + "|" + PURE);
            ObjectNode h = last.get(q.questionId() + "|" + HYB);
            if (p == null || h == null) {
                continue;
            }
            if ("REFUSE".equals(q.expectedDecision())) {
                if (judged(p) && "ANSWER".equals(p.path("actualDecision").asText())) {
                    pQuFalse++;
                }
                if (judged(h) && "ANSWER".equals(h.path("actualDecision").asText())) {
                    hQuFalse++;
                }
            }
            if ("QF-004".equals(q.questionId())) {
                qf004Pure = p.path("actualDecision").asText(null);
                qf004Hyb = h.path("actualDecision").asText(null);
            }
            boolean pFail = "FAIL".equals(p.path("status").asText());
            boolean hFail = "FAIL".equals(h.path("status").asText());
            if (!pFail && hFail) {
                for (JsonNode l : h.path("labels")) {
                    if (l.asText().startsWith("FABRICATED_CITATION") || l.asText().startsWith("REFUSAL_ERROR")) {
                        newCitationRefusalFail.add(q.questionId() + ":" + l.asText());
                        break;
                    }
                }
            }
        }
        boolean qf004Flipped = "REFUSE".equals(qf004Pure) && "ANSWER".equals(qf004Hyb);

        // P6 决策
        double dRecall = deltas.get("recallMacro")[1] == null || deltas.get("recallMacro")[0] == null
                ? Double.NaN
                : (deltas.get("recallMacro")[1] - deltas.get("recallMacro")[0]) * 100;
        double dPrecision = deltas.get("precisionMacro")[1] == null || deltas.get("precisionMacro")[0] == null
                ? Double.NaN
                : (deltas.get("precisionMacro")[1] - deltas.get("precisionMacro")[0]) * 100;
        boolean keepGate = !Double.isNaN(dRecall) && dRecall >= 0
                && !Double.isNaN(dPrecision) && dPrecision >= 5.0
                && hQuFalse <= pQuFalse
                && !Double.isNaN(timeRatio) && timeRatio <= 2.0
                && newCitationRefusalFail.isEmpty();
        boolean rollbackGate = (!Double.isNaN(dPrecision) && dPrecision < 1.0)
                || (!Double.isNaN(dRecall) && dRecall < 0)
                || qf004Flipped;
        String verdict = keepGate ? "KEEP" : rollbackGate ? "ROLLBACK" : "INCONCLUSIVE";

        // 天花板效应计数（P6 附注）
        int ceilingCases = 0;
        for (Dataset.Question q : dataset.questions()) {
            ObjectNode p = last.get(q.questionId() + "|" + PURE);
            if (p != null && p.hasNonNull("recall") && p.get("recall").asDouble() < 1.0) {
                ceilingCases++;
            }
        }
        ObjectNode decision = report.putObject("decision");
        decision.put("verdict", verdict);
        decision.put("deltaRecallPp", Double.isNaN(dRecall) ? null : dRecall);
        decision.put("deltaPrecisionPp", Double.isNaN(dPrecision) ? null : dPrecision);
        decision.put("quFalseAnswer", pQuFalse + " -> " + hQuFalse);
        decision.put("quFalseDelta", hQuFalse - pQuFalse);
        decision.put("qf004PureVsHyb", qf004Pure + " -> " + qf004Hyb + (qf004Flipped ? "（翻案）" : ""));
        decision.put("newCitationRefusalFail", toArray(newCitationRefusalFail));
        decision.put("timeRatio", Double.isNaN(timeRatio) ? null : timeRatio);
        decision.put("pureRecallImprovableCases", ceilingCases);
        decision.put("deterministic", deterministic);
        decision.put("rule", "KEEP=Δrecall≥0pp∧Δprecision≥+5pp∧QU误检不增∧Δt≤100%∧无新增引用/拒答FAIL；"
                + "ROLLBACK=Δprecision<+1pp∨召回净损失∨QF-004翻案；均不满足=INCONCLUSIVE");
        if (ceilingCases < 2) {
            decision.put("ceilingNote", "可提升召回案例 < 2（天花板效应）：召回维度按规则如实计入，"
                    + "案例佐证以 Precision/MRR/排序变化替代（P6 冻结附注）。");
        }

        // 案例明细（逐题现算）
        ArrayNode cases = report.putArray("cases");
        StringBuilder caseMd = new StringBuilder("# D38-R02 逐题案例对比（由原始记录现算）\n\n");
        caseMd.append("| 题号 | 场景 | 变化类型 | 说明 |\n|---|---|---|---|\n");
        int caseNo = 0;
        for (Dataset.Question q : dataset.questions()) {
            ObjectNode p = last.get(q.questionId() + "|" + PURE);
            ObjectNode h = last.get(q.questionId() + "|" + HYB);
            if (p == null || h == null) {
                continue;
            }
            List<String> types = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            Map<String, Integer> pRank = rankMap(p), hRank = rankMap(h);
            Set<String> gained = new LinkedHashSet<>(relevantIds(h));
            gained.removeAll(relevantIds(p));
            Set<String> lost = new LinkedHashSet<>(relevantIds(p));
            lost.removeAll(relevantIds(h));
            if (!gained.isEmpty()) {
                types.add("RECALL_GAIN");
                notes.add("新增相关命中 " + gained);
            }
            if (!lost.isEmpty()) {
                types.add("RECALL_LOSS");
                notes.add("丢失相关命中 " + lost);
            }
            String pTop = firstLogical(p), hTop = firstLogical(h);
            if (pTop != null && !pTop.equals(hTop)) {
                types.add("TOP1_MOVE");
                notes.add("top1 " + shortId(pTop) + "→" + shortId(hTop));
            }
            for (String id : relevantIds(p)) {
                if (hRank.containsKey(id) && pRank.containsKey(id) && !pRank.get(id).equals(hRank.get(id))) {
                    types.add("RANK_MOVE");
                    notes.add(shortId(id) + " 名次 " + pRank.get(id) + "→" + hRank.get(id));
                }
            }
            int pNoise = noiseCount(p), hNoise = noiseCount(h);
            if (hNoise > pNoise) {
                types.add("NOISE_ADD");
                notes.add("非相关命中 " + pNoise + "→" + hNoise);
            }
            if (judged(p) && judged(h) && !String.valueOf(p.path("actualDecision").asText())
                    .equals(h.path("actualDecision").asText())) {
                types.add("DECISION_FLIP");
                notes.add("决策 " + p.path("actualDecision").asText() + "→" + h.path("actualDecision").asText());
            }
            if (types.isEmpty()) {
                continue;
            }
            caseNo++;
            ObjectNode c = cases.addObject();
            c.put("questionId", q.questionId());
            c.put("scenario", q.scenario());
            c.put("question", q.question());
            c.set("types", toArray(types));
            c.set("notes", toArray(notes));
            caseMd.append("| ").append(q.questionId()).append(" | ").append(q.scenario()).append(" | ")
                    .append(String.join("+", types)).append(" | ").append(String.join("；", notes)).append(" |\n");
        }
        report.put("changedCaseCount", caseNo);
        caseMd.append("\n共 ").append(caseNo).append(" 题存在可观察变化（其余两策略逐题质量投影一致）。\n");

        // 分场景小结
        ObjectNode byScenario = report.putObject("byScenario");
        for (String prefix : List.of("QA", "QU", "QT", "QF")) {
            List<Double> pr = new ArrayList<>(), hr = new ArrayList<>(), pp = new ArrayList<>(), hp = new ArrayList<>();
            int n = 0;
            for (Dataset.Question q : dataset.questions()) {
                if (!q.questionId().startsWith(prefix + "-")) {
                    continue;
                }
                n++;
                ObjectNode a = last.get(q.questionId() + "|" + PURE);
                ObjectNode b = last.get(q.questionId() + "|" + HYB);
                addIf(pr, a, "recall");
                addIf(hr, b, "recall");
                addIf(pp, a, "precision");
                addIf(hp, b, "precision");
            }
            ObjectNode s = byScenario.putObject(prefix);
            s.put("questionCount", n);
            s.put("recallPure", mean(pr));
            s.put("recallHybrid", mean(hr));
            s.put("precisionPure", mean(pp));
            s.put("precisionHybrid", mean(hp));
        }

        M.writeValue(outDir.resolve("comparison-report.json").toFile(), report);
        Files.writeString(outDir.resolve("metrics-matrix.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("cases.md"), caseMd.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("decision.txt"),
                "VERDICT=" + verdict + "\nΔrecall=" + signed(dRecall) + "pp Δprecision=" + signed(dPrecision)
                        + "pp QU误检=" + pQuFalse + "→" + hQuFalse + " Δt=" + (Double.isNaN(timeRatio)
                        ? "N/A" : String.format(Locale.ROOT, "%.2fx", timeRatio))
                        + " 新增引用/拒答FAIL=" + newCitationRefusalFail + "\nQF-004=" + qf004Pure + "→" + qf004Hyb
                        + "\ndeterministic=" + deterministic + "\n", StandardCharsets.UTF_8);
        System.out.println("[COMPARE] verdict=" + verdict + " deterministic=" + deterministic
                + " changedCases=" + caseNo + " out=" + outDir);
        if (!deterministic) {
            System.err.println("[CIRCUIT] 确定性未过：结论不得采信（熔断#4/#5）");
        }
    }

    // ---------------- 小工具 ----------------
    private static void addIf(List<Double> list, ObjectNode r, String field) {
        if (r != null && r.hasNonNull(field)) {
            list.add(r.get(field).asDouble());
        }
    }

    private static Double mean(List<Double> v) {
        if (v.isEmpty()) {
            return null;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.size();
    }

    private static boolean judged(ObjectNode r) {
        return !r.path("decisionNotRun").asBoolean(true);
    }

    private static Map<String, Integer> rankMap(ObjectNode r) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (JsonNode h : r.path("hits")) {
            out.put(h.path("logicalChunkId").asText("null"), h.path("rank").asInt());
        }
        return out;
    }

    private static Set<String> relevantIds(ObjectNode r) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode h : r.path("hits")) {
            if (h.path("relevant").asBoolean()) {
                out.add(h.path("logicalChunkId").asText("null"));
            }
        }
        return out;
    }

    private static int noiseCount(ObjectNode r) {
        return r.path("hitCount").asInt() - r.path("relevantCount").asInt();
    }

    private static String firstLogical(ObjectNode r) {
        JsonNode hits = r.path("hits");
        return hits.isEmpty() ? null : hits.get(0).path("logicalChunkId").asText(null);
    }

    private static String shortId(String id) {
        if (id == null) {
            return "null";
        }
        return id.length() > 14 ? id.substring(id.length() - 14) : id;
    }

    private static List<ObjectNode> loadRaw(Path runDir) throws Exception {
        List<ObjectNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(runDir.resolve("raw-results.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add((ObjectNode) M.readTree(line));
            }
        }
        return out;
    }

    private static int lastRoundValues(Map<String, Integer> lastRound) {
        return lastRound.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static Double num(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asDouble() : null;
    }

    private static ArrayNode toArray(List<String> v) {
        ArrayNode a = M.createArrayNode();
        v.forEach(a::add);
        return a;
    }

    private static String fmt(Double v) {
        return v == null ? "N/A" : String.format(Locale.ROOT, "%.6f", v);
    }

    private static String signed(double v) {
        return Double.isNaN(v) ? "N/A" : String.format(Locale.ROOT, "%+.4f", v);
    }

    private Compare() {
    }
}
