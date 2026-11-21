package hybrideval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d37.rag.citation.UrlSafetyPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * hybrideval 自测夹具（C01/C15）：必须先于正式评测全 PASS。
 * 覆盖：指标空集/单元素/多证据/半包含/N/A、RRF/BM25/IDF 手算夹具（C01，直接
 * import 应用真实类做纯函数验证）、稳定次级排序（C16）、三态模式识别八场景、
 * 9 类失败标签正反触发、URL 危险协议夹具（生产 UrlSafetyPolicy 原文件只读）、
 * 数据集 schema 非法拒绝（C04 负例）。
 */
public final class SelfTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        metrics();
        hybridMath();
        stableSort();
        modeRecognition();
        failureLabels();
        urlSafety();
        datasetValidation();
        System.out.println("---- SELFTEST SUMMARY ----");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            FAILURES.forEach(f -> System.out.println("FAILED: " + f));
            System.exit(1);
        }
        System.out.println("ALL SELFTESTS PASSED");
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            FAILURES.add(name);
            System.out.println("[FAIL] " + name);
        }
    }

    // ---------------- 指标 ----------------
    private static void metrics() {
        check("precision 空集 N/A", MetricsCalculator.precision(0, 0) == null);
        check("noise 空集 N/A", MetricsCalculator.noise(0, 0) == null);
        check("rate 分母0 N/A", MetricsCalculator.rate(0, 0) == null);
        check("precision 单元素全相关=1", MetricsCalculator.precision(1, 1) == 1.0);
        check("precision 1/4=0.25", MetricsCalculator.precision(1, 4) == 0.25);
        check("noise 1/4 相关=0.75", Math.abs(MetricsCalculator.noise(1, 4) - 0.75) < 1e-9);
        check("无答案问题 precision=0", MetricsCalculator.precision(0, 5) == 0.0);

        check("单问 Recall 半包含=0.5",
                MetricsCalculator.singleRecall(List.of("C01"), List.of("C01", "C02"),
                        List.of("D1"), List.of("D1")) == 0.5);
        check("单问 Recall 多证据全覆盖=1",
                MetricsCalculator.singleRecall(List.of("C01", "C02", "C03"), List.of("C01", "C02"),
                        List.of("D1"), List.of("D1")) == 1.0);
        check("单问 Recall 无 chunk 标注退化文档级=1",
                MetricsCalculator.singleRecall(List.of(), List.of(), List.of("D1"), List.of("D1")) == 1.0);
        check("单问 Recall 无期望标注 N/A",
                MetricsCalculator.singleRecall(List.of("C01"), List.of(), List.of("D1"), List.of()) == null);
        check("Macro 平均忽略不可判",
                Math.abs(MetricsCalculator.macroAverage(java.util.Arrays.asList(1.0, null, 0.0)) - 0.5) < 1e-9);
        check("Macro 全不可判 N/A",
                MetricsCalculator.macroAverage(java.util.Arrays.asList(null, null)) == null);
        check("HitRate 空 N/A", MetricsCalculator.hitRate(List.of()) == null);
        check("HitRate 2/3", Math.abs(MetricsCalculator.hitRate(List.of(true, false, true)) - 2.0 / 3) < 1e-9);

        MetricsCalculator.TimingStats empty = MetricsCalculator.timing(List.of());
        check("耗时 空集 N/A", empty.mean() == null && empty.count() == 0);
        MetricsCalculator.TimingStats t = MetricsCalculator.timing(List.of(40.0, 10.0, 30.0, 20.0));
        check("耗时 均值=25", t.mean() == 25.0);
        check("耗时 P50 最近秩=20", t.p50() == 20.0);
        check("耗时 P95 最近秩=40", t.p95() == 40.0);
        check("耗时 min/max", t.min() == 10.0 && t.max() == 40.0);
        check("耗时 单元素", MetricsCalculator.timing(List.of(7.0)).p95() == 7.0);
        check("fmt N/A 渲染", "N/A".equals(MetricsCalculator.fmt(null)));
    }

    // ---------------- RRF / BM25 / IDF 手算夹具（C01 核心） ----------------
    private static final double TOL = 1e-9;

    private static void hybridMath() {
        com.zl.d38.hybrid.fusion.RrfFusionService rrf = new com.zl.d38.hybrid.fusion.RrfFusionService();
        // F1：双路同列第一 vs 单路：A=1/61+1/61
        var f1 = rrf.fuse(List.of("A", "B"), List.of("A", "C"), 60, 5);
        check("RRF 双路合计>单路（A rank1）",
                f1.size() == 3 && f1.get(0).chunkId().equals("A")
                        && Math.abs(f1.get(0).rrfScore() - (1 / 61.0 + 1 / 61.0)) < 1e-12
                        && f1.get(0).hitSource().equals("BOTH")
                        && f1.get(0).vectorRank() == 1 && f1.get(0).keywordRank() == 1);
        check("RRF 同分 1/62 tie 按 chunkId 升序（B 前于 C）",
                Math.abs(f1.get(1).rrfScore() - 1 / 62.0) < 1e-12 && f1.get(1).chunkId().equals("B")
                        && f1.get(2).chunkId().equals("C")
                        && f1.get(1).hitSource().equals("VECTOR_ONLY")
                        && f1.get(2).hitSource().equals("KEYWORD_ONLY"));
        // F2：空向量路 → 关键词序保持、向量贡献 0
        var f2 = rrf.fuse(List.of(), List.of("X", "Y"), 60, 5);
        check("RRF 空向量路输出关键词序",
                f2.size() == 2 && f2.get(0).chunkId().equals("X")
                        && Math.abs(f2.get(0).rrfScore() - 1 / 61.0) < 1e-12
                        && f2.get(0).vectorContribution() == 0.0
                        && f2.get(0).hitSource().equals("KEYWORD_ONLY"));
        // F3：双路全空
        check("RRF 双路全空输出空", rrf.fuse(List.of(), List.of(), 60, 5).isEmpty());
        // F4：topK 截断
        check("RRF topK=1 截断", rrf.fuse(List.of("A", "B"), List.of("A", "C"), 60, 1).size() == 1);
        // F5：交错名次 (A,C)=(1,3)/(3,1) vs B=(2,2)。倒数函数 1/x 为凸：
        // 对称点 (61,63) 的倒数均值 > 中点 62 的倒数 → A=C=1/61+1/63=0.0322664584959667 > B=2/62=0.0322580645161290
        // 即“一高一低”双路总贡献大于“双中”（RRF 奖励极端名次的数学性质，夹具推导即教学案例）
        var f5 = rrf.fuse(List.of("A", "B", "C"), List.of("C", "B", "A"), 60, 5);
        check("RRF 凸性：A=C(1/61+1/63) > B(2/62)",
                Math.abs(f5.get(0).rrfScore() - 0.0322664584959667) < 1e-12
                        && f5.get(0).chunkId().equals("A") && f5.get(1).chunkId().equals("C")
                        && Math.abs(f5.get(2).rrfScore() - 2 / 62.0) < 1e-12);
        check("RRF A=C 同分按 chunkId 升序（B 名次3）",
                f5.get(2).chunkId().equals("B") && f5.get(2).hitSource().equals("BOTH")
                        && f5.get(2).vectorRank() == 2 && f5.get(2).keywordRank() == 2);
        // F6：k 变化保序（k=0 时 A/B 双路 {1,2}/{2,1} 总分相等 → tie 按 chunkId：A 前）
        check("RRF k=0 仍确定性（同分按 chunkId）",
                rrf.fuse(List.of("A", "B"), List.of("B", "A"), 0, 5).get(0).chunkId().equals("A"));
        // F7：两路贡献显式字段（B：向量 1/62 + 关键词 1/62 = rrfScore）
        check("RRF 贡献字段=各自 1/(k+rank) 且和=总分",
                Math.abs(f5.get(2).vectorContribution() - 1 / 62.0) < 1e-12
                        && Math.abs(f5.get(2).keywordContribution() - 1 / 62.0) < 1e-12
                        && Math.abs(f5.get(2).vectorContribution() + f5.get(2).keywordContribution()
                        - f5.get(2).rrfScore()) < 1e-12);

        // IDF（BM25 Okapi 变体，N=8）
        check("IDF(8,1)=ln6=1.79175946922805",
                Math.abs(com.zl.d38.hybrid.keyword.InvertedIndex.idf(8, 1) - 1.79175946922805) < 1e-11);
        check("IDF(8,8)≈0.0571584138399486 恒正（全 corpus 词不被清零）",
                Math.abs(com.zl.d38.hybrid.keyword.InvertedIndex.idf(8, 8) - 0.0571584138399486) < 1e-12);
        check("IDF 随 df 单调下降且>0",
                com.zl.d38.hybrid.keyword.InvertedIndex.idf(8, 1)
                        > com.zl.d38.hybrid.keyword.InvertedIndex.idf(8, 4)
                        && com.zl.d38.hybrid.keyword.InvertedIndex.idf(8, 4)
                        > com.zl.d38.hybrid.keyword.InvertedIndex.idf(8, 8)
                        && com.zl.d38.hybrid.keyword.InvertedIndex.idf(8, 8) > 0.0);

        // BM25（idf=ln6, k1=1.2, b=0.75, avgLen=69.5）
        double idf = 1.79175946922805;
        double s11 = com.zl.d38.hybrid.keyword.KeywordSearchService.bm25Score(idf, 1, 69, 69.5, 1.2, 0.75);
        check("BM25 手算 tf=1,len=69 → 1.797048362381", Math.abs(s11 - 1.797048362381) < 1e-9);
        double s12 = com.zl.d38.hybrid.keyword.KeywordSearchService.bm25Score(idf, 2, 69, 69.5, 1.2, 0.75);
        check("BM25 tf 饱和：tf=2 → 2.468664319396 且 < 2×tf1",
                Math.abs(s12 - 2.468664319396) < 1e-9 && s12 < 2 * s11);
        double sLong = com.zl.d38.hybrid.keyword.KeywordSearchService.bm25Score(idf, 1, 100, 69.5, 1.2, 0.75);
        double sShort = com.zl.d38.hybrid.keyword.KeywordSearchService.bm25Score(idf, 1, 50, 69.5, 1.2, 0.75);
        check("BM25 长度归一：1.519046425534(len100) < 2.024085872515(len50)",
                Math.abs(sLong - 1.519046425534) < 1e-9 && sLong < sShort);
        check("BM25 b=0 长度免疫：score=tf1 时恰为 idf",
                Math.abs(com.zl.d38.hybrid.keyword.KeywordSearchService.bm25Score(idf, 1, 10000, 69.5, 1.2, 0.0)
                        - idf) < 1e-12);
    }

    // ---------------- C16 稳定次级排序 ----------------
    private static void stableSort() {
        List<Backends.Hit> hits = new ArrayList<>(List.of(
                hit("C03", 0.5), hit("C01", 0.5), hit("C02", 0.5), hit("C09", 0.9)));
        Backends.sortHits(hits);
        check("次级排序 高分优先", hits.get(0).logicalChunkId().equals("C09"));
        check("次级排序 同分按 chunkId 升序",
                hits.get(1).logicalChunkId().equals("C01")
                        && hits.get(2).logicalChunkId().equals("C02")
                        && hits.get(3).logicalChunkId().equals("C03"));
        List<Backends.Hit> reversed = new ArrayList<>(List.of(
                hit("C02", 0.5), hit("C03", 0.5), hit("C01", 0.5)));
        Backends.sortHits(reversed);
        check("次级排序 与输入顺序无关",
                reversed.get(0).logicalChunkId().equals("C01")
                        && reversed.get(2).logicalChunkId().equals("C03"));
    }

    private static Backends.Hit hit(String id, double sim) {
        return new Backends.Hit(0, id, id, "D", "T", "content", sim, 1 - sim, 1, false);
    }

    // ---------------- C17 模式识别（DUAL/BLOCKED/UNREACHABLE 三态） ----------------
    private static void modeRecognition() {
        // 场景1：应用未启动 → UNREACHABLE（无内存兜底）
        var r1 = EnvProbe.classify(new EnvProbe.ProbeInput(false, false, false, false, false, false, true, true));
        check("场景1 应用未启动 → UNREACHABLE", r1.backend().equals("UNREACHABLE") && r1.mode().equals("OFFLINE"));
        // 场景2：应用启动 + 双端点 ok + 无密钥 → DUAL/OFFLINE
        var r2 = EnvProbe.classify(new EnvProbe.ProbeInput(false, false, true, true, true, true, false, true));
        check("场景2 双端点ok无密钥 → DUAL/OFFLINE",
                r2.backend().equals("DUAL_STRATEGIES") && r2.mode().equals("OFFLINE"));
        // 场景3：hybrid 端点 404（开关未开）→ BLOCKED，禁止单轨降级（熔断#3）
        var r3 = EnvProbe.classify(new EnvProbe.ProbeInput(true, true, true, true, true, false, true, true));
        check("场景3 hybrid端点缺失 → BLOCKED（禁静默降级）",
                r3.backend().equals("BLOCKED") && r3.degradeReason().contains("hybrid"));
        // 场景4：对照组端点异常 → BLOCKED
        var r4 = EnvProbe.classify(new EnvProbe.ProbeInput(true, true, true, true, false, true, true, true));
        check("场景4 对照组缺失 → BLOCKED", r4.backend().equals("BLOCKED"));
        // 场景5：PG 不可达（两策略同时失效）→ BLOCKED
        var r5 = EnvProbe.classify(new EnvProbe.ProbeInput(false, false, false, true, true, true, false, true));
        check("场景5 PG端口不可达 → BLOCKED", r5.backend().equals("BLOCKED") && r5.degradeReason().contains("PG"));
        // 场景6：语料校验失败 → BLOCKED
        var r6 = EnvProbe.classify(new EnvProbe.ProbeInput(false, false, true, true, true, true, false, false));
        check("场景6 语料校验失败 → BLOCKED", r6.backend().equals("BLOCKED"));
        // 场景7：全量（密钥+deepseek+双端点+PG+chat）→ DUAL/FULL
        var r7 = EnvProbe.classify(new EnvProbe.ProbeInput(true, true, true, true, true, true, true, true));
        check("场景7 全量 → DUAL/FULL",
                r7.backend().equals("DUAL_STRATEGIES") && r7.mode().equals("FULL"));
        // 场景8：密钥在但 AI_MODE=mock → OFFLINE（检索不受影响）
        var r8 = EnvProbe.classify(new EnvProbe.ProbeInput(true, false, true, true, true, true, false, true));
        check("场景8 有密钥非deepseek → DUAL/OFFLINE",
                r8.backend().equals("DUAL_STRATEGIES") && r8.mode().equals("OFFLINE"));
        // 模型超时属于运行时标签而非探测态：验证 TIMEOUT_GENERATION 触发
        var timeout = FailureLabels.evaluate(baseInput().timeoutGeneration(true).build());
        check("场景附 模型超时 → TIMEOUT_GENERATION 标签",
                timeout.labels().contains("TIMEOUT:TIMEOUT_GENERATION") && timeout.fail());
    }

    // ---------------- 9 类标签正反触发 ----------------
    private static final class InputBuilder {
        String expectedDecision = "ANSWER";
        String actualDecision = "ANSWER";
        String retrievalStatus = "SUCCESS";
        String generationStatus = "NOT_CONFIGURED";
        String errorCode = "MODEL_NOT_CONFIGURED";
        String retrievalReasonCode;
        int returnedCount = 3;
        int relevantCount = 3;
        boolean relevanceJudgeable = true;
        boolean hasDocExpectation = true;
        boolean docCovered = true;
        boolean hasChunkExpectation = true;
        boolean allChunksCovered = true;
        boolean top1InExpected = true;
        boolean filterApplied;
        boolean mappingAmbiguous;
        List<String> citationIssueCodes = List.of();
        boolean metadataRequiredMissing;
        boolean pageMissing;
        boolean timeoutRetrieval;
        boolean timeoutGeneration;
        boolean timeoutTotal;
        boolean mustNotViolated;
        boolean decisionNotRun;
        boolean backendBlocked;

        InputBuilder timeoutGeneration(boolean v) { timeoutGeneration = v; return this; }

        FailureLabels.Input build() {
            return new FailureLabels.Input(expectedDecision, actualDecision, retrievalStatus,
                    generationStatus, errorCode, retrievalReasonCode, returnedCount, relevantCount,
                    relevanceJudgeable, hasDocExpectation, docCovered, hasChunkExpectation,
                    allChunksCovered, top1InExpected, filterApplied, mappingAmbiguous,
                    citationIssueCodes, metadataRequiredMissing, pageMissing, timeoutRetrieval,
                    timeoutGeneration, timeoutTotal, mustNotViolated, decisionNotRun, backendBlocked);
        }
    }

    private static InputBuilder baseInput() {
        return new InputBuilder();
    }

    private static void failureLabels() {
        check("干净输入 PASS 无标签", FailureLabels.evaluate(baseInput().build()).labels().isEmpty());

        var b1 = baseInput(); b1.docCovered = false; b1.returnedCount = 0;
        check("MISS_RECALL 空检索归因", FailureLabels.evaluate(b1.build()).labels()
                .contains("MISS_RECALL:MISS_RECALL_EMPTY"));
        var b2 = baseInput(); b2.docCovered = false;
        check("MISS_RECALL 低分归因", FailureLabels.evaluate(b2.build()).labels()
                .contains("MISS_RECALL:MISS_RECALL_LOW_SCORE"));
        var b3 = baseInput(); b3.docCovered = false; b3.filterApplied = true;
        check("MISS_RECALL 过滤归因", FailureLabels.evaluate(b3.build()).labels()
                .contains("MISS_RECALL:MISS_RECALL_FILTERED"));
        var b4 = baseInput(); b4.allChunksCovered = false;
        check("MISS_RECALL 多证据半包含触发", FailureLabels.evaluate(b4.build()).fail());

        var b5 = baseInput(); b5.relevantCount = 1; b5.returnedCount = 4;
        check("HIGH_NOISE precision<0.5", FailureLabels.evaluate(b5.build()).labels()
                .contains("HIGH_NOISE:HIGH_NOISE_PRECISION"));
        var b6 = baseInput(); b6.top1InExpected = false;
        check("HIGH_NOISE 首位不在期望集", FailureLabels.evaluate(b6.build()).labels()
                .contains("HIGH_NOISE:HIGH_NOISE_TOP1"));

        var b7 = baseInput(); b7.citationIssueCodes = List.of("CITATION_UNKNOWN_ID");
        check("FABRICATED_CITATION 触发", FailureLabels.evaluate(b7.build()).labels()
                .contains("FABRICATED_CITATION:CITATION_UNKNOWN_ID"));

        var b8 = baseInput(); b8.actualDecision = "REFUSE";
        check("REFUSAL 误拒（期望答却拒）", FailureLabels.evaluate(b8.build()).labels()
                .contains("REFUSAL_ERROR:REFUSAL_FALSE_NEGATIVE"));
        var b9 = baseInput(); b9.expectedDecision = "REFUSE"; b9.actualDecision = "ANSWER";
        check("REFUSAL 误答（期望拒却答）", FailureLabels.evaluate(b9.build()).labels()
                .contains("REFUSAL_ERROR:REFUSAL_FALSE_POSITIVE"));
        var b10 = baseInput(); b10.expectedDecision = "REFUSE"; b10.actualDecision = "REFUSE";
        b10.errorCode = "RETRIEVAL_FAILED";
        check("REFUSAL 错误码不匹配", FailureLabels.evaluate(b10.build()).labels()
                .contains("REFUSAL_ERROR:REFUSAL_WRONG_CODE"));
        var b10ok = baseInput(); b10ok.expectedDecision = "REFUSE"; b10ok.actualDecision = "REFUSE";
        b10ok.errorCode = "LOW_RELEVANCE"; b10ok.hasDocExpectation = false; b10ok.hasChunkExpectation = false;
        b10ok.relevantCount = 0; b10ok.top1InExpected = false; b10ok.relevanceJudgeable = true;
        check("REFUSAL 正确拒答不误报 REFUSAL_ERROR",
                FailureLabels.evaluate(b10ok.build()).labels().stream().noneMatch(l -> l.startsWith("REFUSAL_ERROR")));

        var b11 = baseInput(); b11.expectedDecision = "CLARIFY"; b11.actualDecision = "REFUSE";
        check("CLARIFY 期望澄清未澄清", FailureLabels.evaluate(b11.build()).labels()
                .contains("WRONG_CLARIFICATION:CLARIFY_EXPECTED_MISSING"));
        var b12 = baseInput(); b12.actualDecision = "CLARIFY";
        check("CLARIFY 不应澄清却澄清", FailureLabels.evaluate(b12.build()).labels()
                .contains("WRONG_CLARIFICATION:CLARIFY_UNEXPECTED"));

        var b13 = baseInput(); b13.metadataRequiredMissing = true;
        check("METADATA 必填缺失 FAIL", FailureLabels.evaluate(b13.build()).fail());
        var b14 = baseInput(); b14.pageMissing = true;
        var r14 = FailureLabels.evaluate(b14.build());
        check("METADATA page缺失仅统计不FAIL",
                r14.labels().contains("METADATA_INCOMPLETE:PAGE_LOCATION_MISSING") && !r14.fail());

        var b15 = baseInput(); b15.timeoutRetrieval = true;
        check("TIMEOUT 检索阶段", FailureLabels.evaluate(b15.build()).labels()
                .contains("TIMEOUT:TIMEOUT_RETRIEVAL"));
        var b16 = baseInput(); b16.timeoutTotal = true;
        check("TIMEOUT 端到端阶段", FailureLabels.evaluate(b16.build()).labels()
                .contains("TIMEOUT:TIMEOUT_TOTAL"));

        var b17 = baseInput(); b17.generationStatus = "FAILED_ERROR"; b17.errorCode = "EMPTY_RESPONSE";
        check("MODEL_ERROR 空响应", FailureLabels.evaluate(b17.build()).labels()
                .contains("MODEL_ERROR:MODEL_EMPTY"));
        var b18 = baseInput(); b18.generationStatus = "FAILED_ERROR"; b18.errorCode = "GENERATION_FAILED";
        check("MODEL_ERROR 通用异常", FailureLabels.evaluate(b18.build()).labels()
                .contains("MODEL_ERROR:MODEL_ERROR_GENERIC"));
        check("MODEL NOT_CONFIGURED 不算错误",
                FailureLabels.evaluate(baseInput().build()).labels().stream().noneMatch(l -> l.startsWith("MODEL_ERROR")));

        var b19 = baseInput(); b19.retrievalStatus = "FAILED"; b19.retrievalReasonCode = "RETRIEVAL_EMBED_FAIL";
        check("RETRIEVAL_ERROR Embedding失败", FailureLabels.evaluate(b19.build()).labels()
                .contains("RETRIEVAL_ERROR:RETRIEVAL_EMBED_FAIL"));
        var b20 = baseInput(); b20.retrievalStatus = "FAILED";
        check("RETRIEVAL_ERROR 后端异常", FailureLabels.evaluate(b20.build()).labels()
                .contains("RETRIEVAL_ERROR:RETRIEVAL_BACKEND_FAIL"));
        var b21 = baseInput(); b21.retrievalStatus = "EMPTY"; b21.returnedCount = 0;
        b21.hasDocExpectation = false; b21.hasChunkExpectation = false; b21.relevanceJudgeable = false;
        b21.expectedDecision = "REFUSE"; b21.actualDecision = "REFUSE"; b21.errorCode = "NO_EVIDENCE";
        check("EMPTY 是成功态不触发 RETRIEVAL_ERROR",
                FailureLabels.evaluate(b21.build()).labels().stream().noneMatch(l -> l.startsWith("RETRIEVAL_ERROR")));

        var b22 = baseInput(); b22.backendBlocked = true;
        check("BLOCKED 状态独立于 FAIL", FailureLabels.evaluate(b22.build()).status().equals("BLOCKED"));
    }

    // ---------------- C11 URL 安全夹具（生产类原文件） ----------------
    private static void urlSafety() {
        String[] dangerous = {
                "javascript:alert(1)", "data:text/html;base64,PHNjcmlwdD4=",
                "file:///C:/Windows/win.ini", "ftp://internal/file",
                "C:\\Windows\\System32\\config", "E:/AI/project/secret.txt"};
        boolean allBlocked = true;
        for (String u : dangerous) {
            allBlocked &= UrlSafetyPolicy.sanitize(u) == null && UrlSafetyPolicy.isDangerous(u);
        }
        check("危险协议/盘符路径全拦截", allBlocked);
        String[] localPrivate = {
                "http://localhost/x", "http://127.0.0.1/", "http://192.168.200.153/",
                "http://10.0.0.1/", "http://172.16.0.1/", "http://[::1]/", "http:///nohost"};
        boolean allNull = true;
        for (String u : localPrivate) {
            allNull &= UrlSafetyPolicy.sanitize(u) == null;
        }
        check("localhost/环回/内网/无主机全拒绝", allNull);
        check("null 输入拒绝", UrlSafetyPolicy.sanitize(null) == null);
        // 字面量公网 IP：不触发 DNS，确定性放行（验证白名单方向不误杀）
        check("公网字面量 http 放行",
                "http://93.184.216.34/doc".equals(UrlSafetyPolicy.sanitize("http://93.184.216.34/doc")));
        check("公网字面量 https 放行",
                "https://93.184.216.34/".equals(UrlSafetyPolicy.sanitize("https://93.184.216.34/")));
    }

    // ---------------- C04 数据集 schema 负例 ----------------
    private static void datasetValidation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path dir = Files.createTempDirectory("d37-selftest");
        String validLine = "{\"questionId\":\"QA-001\",\"scenario\":\"ANSWERABLE\",\"question\":\"q\","
                + "\"expectedDecision\":\"ANSWER\",\"expectedDocumentIds\":[\"D\"],\"expectedChunkIds\":[],"
                + "\"mustNotReturnDocumentIds\":[],\"expectedTerms\":[],\"missingInformation\":[],"
                + "\"annotatedAt\":\"2026-11-20T00:00:00+08:00\",\"annotator\":\"manual-v1\",\"notes\":\"n\"}";
        Path bad = dir.resolve("bad-json.jsonl");
        Files.writeString(bad, "{not json", StandardCharsets.UTF_8);
        check("非法 JSON 被拒", rejects(() -> Dataset.loadAndValidate(bad, mapper)));
        Path unfrozen = dir.resolve("unfrozen.jsonl");
        Files.writeString(unfrozen, validLine.replace("\"2026-11-20T00:00:00+08:00\"", "null"),
                StandardCharsets.UTF_8);
        check("未冻结（annotatedAt=null）被拒", rejects(() -> Dataset.loadAndValidate(unfrozen, mapper)));
        Path wrongCount = dir.resolve("wrong-count.jsonl");
        Files.writeString(wrongCount, validLine + "\n", StandardCharsets.UTF_8);
        check("非 20 问被拒", rejects(() -> Dataset.loadAndValidate(wrongCount, mapper)));
        Path badScenario = dir.resolve("bad-scenario.jsonl");
        Files.writeString(badScenario, validLine.replace("\"ANSWERABLE\"", "\"UNKNOWN\""),
                StandardCharsets.UTF_8);
        check("非法 scenario 被拒", rejects(() -> Dataset.loadAndValidate(badScenario, mapper)));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static boolean rejects(ThrowingRunnable r) {
        try {
            r.run();
            return false;
        } catch (Exception ex) {
            return true;
        }
    }
}
