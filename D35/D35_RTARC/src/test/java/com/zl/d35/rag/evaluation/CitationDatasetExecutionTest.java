package com.zl.d35.rag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d35.document.mapper.DocumentChunkIndexMapper;
import com.zl.d35.document.mapper.KnowledgeDocumentMapper;
import com.zl.d35.document.po.DocumentChunkIndexPO;
import com.zl.d35.document.po.KnowledgeDocumentPO;
import com.zl.d35.document.retrieval.dto.SimilarityMatchView;
import com.zl.d35.rag.assessment.FactClaimExtractor;
import com.zl.d35.rag.citation.CitationVerificationService;
import com.zl.d35.rag.citation.SnippetExtractor;
import com.zl.d35.rag.config.RagProperties;
import com.zl.d35.rag.context.ContextAssemblyResult;
import com.zl.d35.rag.context.ContextAssemblyService;
import com.zl.d35.rag.context.EvidenceItem;
import com.zl.d35.rag.model.RagErrorCode;
import com.zl.d35.rag.validation.CitationValidationResult;
import com.zl.d35.rag.validation.ResponseValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 评测数据集执行器：用生产组件真实执行 {@code citation-evaluation-dataset.json}，
 * 并把 actualResult / passed / failureReason 写回结果文件。
 *
 * <p><b>执行模式与限制</b>：本执行器不启动 Spring 容器、不调用真实 ChatModel、不连接真实
 * pgvector；verification 用例用受控夹具表达权威持久化状态（文档行与 Chunk 归属），
 * validation 用例直接执行生产校验逻辑。因此结果是“校验器级别真实执行”，
 * <b>不等价于真实 LLM + 真实数据库端到端</b>，不得据此宣称端到端通过。</p>
 *
 * <p>默认不纳入常规回归（需显式设置 D35_DATASET_RUN=true）；执行后会写入：
 * docs/evidence/D35/remediation/D35-评测集执行结果.json。</p>
 */
@EnabledIfEnvironmentVariable(named = "D35_DATASET_RUN", matches = "true")
class CitationDatasetExecutionTest {

    private static final String DATASET_RELATIVE =
            "docs/evidence/D35/citation-evaluation-dataset.json";
    private static final String RESULT_RELATIVE =
            "docs/evidence/D35/remediation/D35-评测集执行结果.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponseValidationService validationService =
            new ResponseValidationService(new FactClaimExtractor());

    @Test
    void executeDatasetAndWriteResults() throws Exception {
        Path repoRoot = resolveRepoRoot();
        Path datasetPath = repoRoot.resolve(DATASET_RELATIVE);
        Path resultPath = repoRoot.resolve(RESULT_RELATIVE);
        assertTrue(Files.exists(datasetPath), "数据集不存在: " + datasetPath);

        JsonNode dataset = mapper.readTree(datasetPath.toFile());
        JsonNode cases = dataset.path("cases");

        ObjectNode root = mapper.createObjectNode();
        ObjectNode meta = root.putObject("meta");
        meta.put("iteration", dataset.path("meta").path("iteration").asText("D35"));
        meta.put("datasetVersion", dataset.path("meta").path("version").asText("unknown"));
        meta.put("datasetPath", DATASET_RELATIVE);
        meta.put("executedAt", OffsetDateTime.now().toString());
        meta.put("executionMode", "CONTROLLED_FIXTURE_VALIDATOR_LEVEL");
        meta.put("limitations",
                "未启动 Spring 容器、未调用真实 ChatModel、未连接真实 pgvector；"
                        + "verification 用例以受控夹具表达权威持久化状态。"
                        + "结果为校验器级别真实执行，不等价于真实端到端。");

        ArrayNode resultCases = root.putArray("cases");
        int executed = 0;
        int passedCount = 0;
        int failedCount = 0;
        int notExecuted = 0;
        List<String> failedIds = new ArrayList<>();

        for (JsonNode caseNode : cases) {
            String caseId = caseNode.path("caseId").asText();
            String executor = caseNode.path("executor").asText("not_executable_here");
            ObjectNode resultCase = mapper.createObjectNode();
            resultCase.put("caseId", caseId);
            resultCase.put("scenario", caseNode.path("scenario").asText(""));
            resultCase.put("executor", executor);

            if ("not_executable_here".equals(executor)) {
                ObjectNode actual = mapper.createObjectNode();
                actual.put("executed", false);
                actual.put("note", "本数据集执行器不覆盖该层；由 D35 其他测试（Node 前端测试/控制器契约测试）负责，"
                        + "不得据此判定通过。");
                resultCase.set("actualResult", actual);
                resultCase.putNull("passed");
                resultCase.putNull("failureReason");
                notExecuted++;
                resultCases.add(resultCase);
                continue;
            }

            executed++;
            ObjectNode actual = executeCase(caseNode, executor);
            resultCase.set("actualResult", actual);

            List<String> failures = new ArrayList<>();
            boolean passed = evaluate(caseNode.path("expectedStatus"), actual, failures);
            resultCase.put("passed", passed);
            if (passed) {
                passedCount++;
                resultCase.putNull("failureReason");
            } else {
                failedCount++;
                failedIds.add(caseId);
                resultCase.put("failureReason", String.join("；", failures));
            }
            resultCases.add(resultCase);
        }

        ObjectNode summary = root.putObject("summary");
        summary.put("total", cases.size());
        summary.put("executed", executed);
        summary.put("passed", passedCount);
        summary.put("failed", failedCount);
        summary.put("notExecuted", notExecuted);
        summary.put("failedCaseIds", mapper.valueToTree(failedIds));

        Files.createDirectories(resultPath.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(resultPath.toFile(), root);

        assertEquals(0, failedCount,
                "评测集存在失败用例: " + String.join(",", failedIds)
                        + "，详见 " + RESULT_RELATIVE);
    }

    // ---- 执行器 ----

    private ObjectNode executeCase(JsonNode caseNode, String executor) throws IOException {
        ObjectNode actual = mapper.createObjectNode();
        actual.put("executed", true);
        switch (executor) {
            case "validation" -> executeValidation(caseNode, actual);
            case "verification" -> executeVerification(caseNode, actual);
            case "context" -> executeContext(caseNode, actual);
            default -> throw new IllegalArgumentException("未知 executor: " + executor);
        }
        return actual;
    }

    private void executeValidation(JsonNode caseNode, ObjectNode actual) {
        List<EvidenceItem> evidence = toEvidence(caseNode.path("retrievedChunks"));
        CitationValidationResult result =
                validationService.validate(caseNode.path("modelAnswer").asText(null), evidence);

        ObjectNode node = actual.putObject("validation");
        node.put("validationPassed", result.validationPassed());
        node.put("legalCitationPassed", result.legalCitationPassed());
        node.set("invalidCitations", toArray(result.invalidCitations()));
        node.set("unsupportedClaims", toArray(result.unsupportedClaims()));
        node.set("claimCitationMismatches", toArray(result.claimCitationMismatches()));
        node.set("fabricatedUrls", toArray(result.fabricatedUrls()));

        actual.put("derivedErrorCode", deriveValidationError(result).name());
        actual.put("answerSuppressed", !result.validationPassed());
    }

    private void executeVerification(JsonNode caseNode, ObjectNode actual) {
        List<EvidenceItem> evidence = toEvidence(caseNode.path("retrievedChunks"));
        JsonNode verificationNode = caseNode.path("verification");

        KnowledgeDocumentMapper documentMapper = Mockito.mock(KnowledgeDocumentMapper.class);
        DocumentChunkIndexMapper chunkMapper = Mockito.mock(DocumentChunkIndexMapper.class);
        when(documentMapper.selectList(any())).thenReturn(toDocumentPOs(verificationNode.path("documents")));
        when(chunkMapper.selectList(any())).thenReturn(toChunkPOs(verificationNode.path("chunks")));

        CitationVerificationService service =
                new CitationVerificationService(documentMapper, chunkMapper);
        CitationVerificationService.CitationVerification verification = service.verify(evidence);

        ObjectNode node = actual.putObject("verification");
        node.put("validCount", verification.validItems().size());
        node.put("removedCount", verification.removed().size());
        ArrayNode removed = node.putArray("removed");
        for (CitationVerificationService.RemovedSource source : verification.removed()) {
            ObjectNode r = removed.addObject();
            r.put("sourceRef", source.sourceRef());
            r.put("documentId", source.documentId());
            r.put("chunkId", source.chunkId());
            r.put("reasonCode", source.reasonCode().name());
        }

        actual.set("validSourceRefs", toArray(verification.validItems().stream()
                .map(EvidenceItem::getSourceRef).toList()));

        ObjectNode reasonCodes = actual.putObject("removedReasonCodes");
        for (CitationVerificationService.RemovedSource source : verification.removed()) {
            reasonCodes.put(source.sourceRef(), source.reasonCode().name());
        }

        actual.put("derivedErrorCode", selectRemovalCode(verification).name());
        actual.put("answerSuppressed", verification.validItems().isEmpty());
    }

    private void executeContext(JsonNode caseNode, ObjectNode actual) {
        RagProperties properties = new RagProperties();
        properties.setContextBudgetChars(6000);
        properties.setQuotedSnippetMaxChars(200);

        List<SimilarityMatchView> matches = new ArrayList<>();
        for (JsonNode chunk : caseNode.path("retrievedChunks")) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            putIfPresent(metadata, "documentId", text(chunk, "documentId"));
            putIfPresent(metadata, "title", text(chunk, "title"));
            putIfPresent(metadata, "version", intOrNull(chunk, "version"));
            putIfPresent(metadata, "strategy", text(chunk, "strategy"));
            putIfPresent(metadata, "chunkIndex", intOrNull(chunk, "chunkIndex"));
            putIfPresent(metadata, "page", intOrNull(chunk, "page"));
            putIfPresent(metadata, "lastPage", intOrNull(chunk, "lastPage"));
            putIfPresent(metadata, "paragraphIndex", intOrNull(chunk, "paragraphIndex"));
            matches.add(SimilarityMatchView.from(text(chunk, "chunkId"),
                    text(chunk, "content"), metadata, null, null));
        }

        ContextAssemblyResult assembly = new ContextAssemblyService(properties).assemble(matches);
        List<EvidenceItem> evidence = assembly.getEvidence();

        if (caseNode.path("applyMarkCited").asBoolean(false)) {
            KnowledgeDocumentMapper documentMapper = Mockito.mock(KnowledgeDocumentMapper.class);
            DocumentChunkIndexMapper chunkMapper = Mockito.mock(DocumentChunkIndexMapper.class);
            new CitationVerificationService(documentMapper, chunkMapper)
                    .markCited(caseNode.path("modelAnswer").asText(null), evidence);
        }

        ArrayNode evidenceArray = actual.putArray("evidence");
        Map<String, String> snippets = new LinkedHashMap<>();
        boolean distinct = true;
        boolean matchesOwnContent = true;
        int maxLength = 0;
        for (EvidenceItem item : evidence) {
            ObjectNode e = evidenceArray.addObject();
            e.put("sourceRef", item.getSourceRef());
            e.put("documentId", item.getDocumentId());
            e.put("chunkId", item.getChunkId());
            e.put("page", item.getPage());
            e.put("lastPage", item.getLastPage());
            e.put("paragraphIndex", item.getParagraphIndex());
            e.put("chunkIndex", item.getChunkIndex());
            e.put("quotedSnippet", item.getQuotedSnippet());
            e.put("cited", item.isCited());

            String snippet = item.getQuotedSnippet();
            if (snippet != null) {
                maxLength = Math.max(maxLength, snippet.length());
                if (snippets.containsValue(snippet)) {
                    distinct = false;
                }
                snippets.put(item.getSourceRef(), snippet);
                String plain = snippet.endsWith("…")
                        ? snippet.substring(0, snippet.length() - 1) : snippet;
                if (item.getContent() == null || !item.getContent().contains(plain.trim())) {
                    matchesOwnContent = false;
                }
            }
        }

        actual.set("citationIds", toArray(evidence.stream()
                .map(EvidenceItem::getSourceRef).toList()));
        actual.set("pageByRef", refMap(evidence, EvidenceItem::getPage));
        actual.set("lastPageByRef", refMap(evidence, EvidenceItem::getLastPage));
        actual.set("paragraphIndexByRef", refMap(evidence, EvidenceItem::getParagraphIndex));
        actual.set("chunkIndexByRef", refMap(evidence, EvidenceItem::getChunkIndex));
        actual.set("citedRefs", toArray(evidence.stream()
                .filter(EvidenceItem::isCited).map(EvidenceItem::getSourceRef).toList()));
        actual.put("distinctQuotedSnippets", distinct);
        actual.put("snippetMatchesOwnContent", matchesOwnContent);
        actual.put("maxQuotedSnippetLength", maxLength);
        actual.put("quotedSnippetLimit", properties.getQuotedSnippetMaxChars());

        ArrayNode excluded = actual.putArray("excluded");
        for (ContextAssemblyResult.Excluded item : assembly.getExcluded()) {
            ObjectNode e = excluded.addObject();
            e.put("chunkId", item.chunkId());
            e.put("reason", item.reason());
        }
        actual.put("derivedErrorCode", RagErrorCode.NONE.name());
        actual.put("answerSuppressed", false);
    }

    // ---- 断言比较 ----

    private boolean evaluate(JsonNode expected, ObjectNode actual, List<String> failures) {
        if (expected == null || expected.isMissingNode()) {
            failures.add("缺少 expectedStatus");
            return false;
        }
        var fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            switch (key) {
                case "validationPassed" -> checkBoolean(failures, key,
                        actual.path("validation").path("validationPassed").asBoolean(false),
                        value.asBoolean());
                case "answerSuppressed" -> checkBoolean(failures, key,
                        actual.path("answerSuppressed").asBoolean(false), value.asBoolean());
                case "derivedErrorCode" -> checkText(failures, key,
                        actual.path("derivedErrorCode").asText(), value.asText());
                case "containsInvalidCitations" -> checkContainsAll(failures, key,
                        actual.path("validation").path("invalidCitations"), value);
                case "containsUnsupportedClaims" -> checkContainsAll(failures, key,
                        actual.path("validation").path("unsupportedClaims"), value);
                case "notContainsUnsupportedClaims" -> checkContainsNone(failures, key,
                        actual.path("validation").path("unsupportedClaims"), value);
                case "claimCitationMismatchContains" -> checkMismatch(failures,
                        actual.path("validation").path("claimCitationMismatches"), value);
                case "fabricatedUrlCount" -> checkInt(failures, key,
                        actual.path("validation").path("fabricatedUrls").size(), value.asInt());
                case "validSourceRefs" -> checkList(failures, key,
                        actual.path("validSourceRefs"), value);
                case "citationIds" -> checkList(failures, key, actual.path("citationIds"), value);
                case "citedRefs" -> checkList(failures, key, actual.path("citedRefs"), value);
                case "removedReasonCodes" -> checkRemovedCodes(failures, actual, value);
                case "pageByRef" -> checkRefMap(failures, "pageByRef",
                        actual.path("pageByRef"), value);
                case "lastPageByRef" -> checkRefMap(failures, "lastPageByRef",
                        actual.path("lastPageByRef"), value);
                case "paragraphIndexByRef" -> checkRefMap(failures, "paragraphIndexByRef",
                        actual.path("paragraphIndexByRef"), value);
                case "notEqualToChunkIndex" -> checkNotEqualToChunkIndex(failures,
                        actual, value.asBoolean());
                case "distinctQuotedSnippets" -> checkBoolean(failures, key,
                        actual.path("distinctQuotedSnippets").asBoolean(false), value.asBoolean());
                case "snippetMatchesOwnContent" -> checkBoolean(failures, key,
                        actual.path("snippetMatchesOwnContent").asBoolean(false), value.asBoolean());
                case "maxQuotedSnippetLength" -> checkInt(failures, key,
                        actual.path("maxQuotedSnippetLength").asInt(), value.asInt());
                case "quotedSnippetLimit" -> checkInt(failures, key,
                        actual.path("quotedSnippetLimit").asInt(), value.asInt());
                default -> failures.add("未支持的断言键: " + key);
            }
        }
        return failures.isEmpty();
    }

    private void checkBoolean(List<String> failures, String key, boolean actual, boolean expected) {
        if (actual != expected) {
            failures.add(key + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private void checkInt(List<String> failures, String key, int actual, int expected) {
        if (actual != expected) {
            failures.add(key + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private void checkText(List<String> failures, String key, String actual, String expected) {
        if (!expected.equals(actual)) {
            failures.add(key + " 期望=" + expected + " 实际=" + actual);
        }
    }

    private void checkContainsAll(List<String> failures, String key,
                                  JsonNode actualArray, JsonNode expectedArray) {
        Set<String> actual = new LinkedHashSet<>();
        actualArray.forEach(node -> actual.add(node.asText()));
        expectedArray.forEach(node -> {
            if (!actual.contains(node.asText())) {
                failures.add(key + " 缺少 " + node.asText() + "，实际=" + actual);
            }
        });
    }

    private void checkContainsNone(List<String> failures, String key,
                                   JsonNode actualArray, JsonNode expectedArray) {
        Set<String> actual = new LinkedHashSet<>();
        actualArray.forEach(node -> actual.add(node.asText()));
        expectedArray.forEach(node -> {
            if (actual.contains(node.asText())) {
                failures.add(key + " 不应包含 " + node.asText());
            }
        });
    }

    private void checkMismatch(List<String> failures, JsonNode actualArray, JsonNode expectedArray) {
        if (expectedArray.isEmpty()) {
            if (!actualArray.isEmpty()) {
                failures.add("claimCitationMismatchContains 期望为空，实际=" + actualArray);
            }
            return;
        }
        List<String> actual = new ArrayList<>();
        actualArray.forEach(node -> actual.add(node.asText()));
        expectedArray.forEach(node -> {
            String needle = node.asText();
            boolean found = actual.stream().anyMatch(text -> text.contains(needle));
            if (!found) {
                failures.add("claimCitationMismatchContains 缺少 " + needle + "，实际=" + actual);
            }
        });
    }

    private void checkList(List<String> failures, String key, JsonNode actual, JsonNode expected) {
        List<String> actualList = new ArrayList<>();
        actual.forEach(node -> actualList.add(node.asText()));
        List<String> expectedList = new ArrayList<>();
        expected.forEach(node -> expectedList.add(node.asText()));
        if (!actualList.equals(expectedList)) {
            failures.add(key + " 期望=" + expectedList + " 实际=" + actualList);
        }
    }

    private void checkRemovedCodes(List<String> failures, ObjectNode actual, JsonNode expected) {
        JsonNode actualCodes = actual.path("removedReasonCodes");
        var fields = expected.fields();
        int expectedCount = 0;
        while (fields.hasNext()) {
            expectedCount++;
            Map.Entry<String, JsonNode> entry = fields.next();
            String ref = entry.getKey();
            String expectedCode = entry.getValue().asText();
            String actualCode = actualCodes.path(ref).asText("<缺失>");
            if (!expectedCode.equals(actualCode)) {
                failures.add("removedReasonCodes[" + ref + "] 期望=" + expectedCode
                        + " 实际=" + actualCode);
            }
        }
        if (actualCodes.size() != expectedCount) {
            failures.add("removedReasonCodes 条目数 期望=" + expectedCount
                    + " 实际=" + actualCodes.size());
        }
    }

    private void checkRefMap(List<String> failures, String key,
                             JsonNode actual, JsonNode expected) {
        var fields = expected.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String ref = entry.getKey();
            JsonNode expectedValue = entry.getValue();
            JsonNode actualValue = actual.path(ref);
            if (expectedValue.isNull()) {
                if (!actualValue.isMissingNode() && !actualValue.isNull()) {
                    failures.add(key + "[" + ref + "] 期望=null 实际=" + actualValue);
                }
            } else if (!expectedValue.equals(actualValue)) {
                failures.add(key + "[" + ref + "] 期望=" + expectedValue + " 实际=" + actualValue);
            }
        }
    }

    private void checkNotEqualToChunkIndex(List<String> failures, ObjectNode actual,
                                           boolean expected) {
        if (!expected) {
            return;
        }
        JsonNode pages = actual.path("pageByRef");
        JsonNode chunkIndexes = actual.path("chunkIndexByRef");
        var fields = pages.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String ref = entry.getKey();
            JsonNode page = entry.getValue();
            JsonNode chunkIndex = chunkIndexes.path(ref);
            if (!page.isNull() && chunkIndex.isNumber() && page.asInt() == chunkIndex.asInt()) {
                failures.add("pageByRef[" + ref + "] 不应等于 chunkIndex=" + chunkIndex.asInt());
            }
        }
    }

    // ---- 数据转换 ----

    private List<EvidenceItem> toEvidence(JsonNode chunks) {
        List<EvidenceItem> evidence = new ArrayList<>();
        for (JsonNode chunk : chunks) {
            EvidenceItem item = new EvidenceItem();
            item.setSourceRef(text(chunk, "sourceRef"));
            item.setDocumentId(text(chunk, "documentId"));
            item.setChunkId(text(chunk, "chunkId"));
            item.setTitle(text(chunk, "title"));
            item.setContent(text(chunk, "content"));
            item.setVersion(intOrNull(chunk, "version"));
            item.setChunkIndex(intOrNull(chunk, "chunkIndex"));
            item.setStrategy(text(chunk, "strategy"));
            item.setPage(intOrNull(chunk, "page"));
            item.setLastPage(intOrNull(chunk, "lastPage"));
            item.setParagraphIndex(intOrNull(chunk, "paragraphIndex"));
            item.setQuotedSnippet(SnippetExtractor.extract(text(chunk, "content"), 200));
            evidence.add(item);
        }
        return evidence;
    }

    private List<KnowledgeDocumentPO> toDocumentPOs(JsonNode documents) {
        List<KnowledgeDocumentPO> rows = new ArrayList<>();
        for (JsonNode document : documents) {
            KnowledgeDocumentPO po = new KnowledgeDocumentPO();
            po.setDocumentId(text(document, "documentId"));
            po.setLifecycleStatus(text(document, "lifecycleStatus"));
            rows.add(po);
        }
        return rows;
    }

    private List<DocumentChunkIndexPO> toChunkPOs(JsonNode chunks) {
        List<DocumentChunkIndexPO> rows = new ArrayList<>();
        for (JsonNode chunk : chunks) {
            DocumentChunkIndexPO po = new DocumentChunkIndexPO();
            po.setChunkId(text(chunk, "chunkId"));
            po.setDocumentId(text(chunk, "documentId"));
            rows.add(po);
        }
        return rows;
    }

    private RagErrorCode deriveValidationError(CitationValidationResult result) {
        if (!result.fabricatedUrls().isEmpty()) {
            return RagErrorCode.FABRICATED_URL;
        }
        if (!result.validationPassed()) {
            return RagErrorCode.GENERATION_VALIDATION_FAILED;
        }
        return RagErrorCode.NONE;
    }

    private RagErrorCode selectRemovalCode(
            CitationVerificationService.CitationVerification verification) {
        List<RagErrorCode> codes = verification.removed().stream()
                .map(CitationVerificationService.RemovedSource::reasonCode)
                .toList();
        if (codes.contains(RagErrorCode.CITATION_TARGET_DELETED)) {
            return RagErrorCode.CITATION_TARGET_DELETED;
        }
        if (codes.contains(RagErrorCode.CITATION_OWNERSHIP_MISMATCH)) {
            return RagErrorCode.CITATION_OWNERSHIP_MISMATCH;
        }
        if (codes.contains(RagErrorCode.EVIDENCE_NOT_EFFECTIVE)) {
            return RagErrorCode.EVIDENCE_NOT_EFFECTIVE;
        }
        return RagErrorCode.NONE;
    }

    // ---- 工具 ----

    private Path resolveRepoRoot() {
        String configured = System.getProperty("d35.repo.root");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.dir")).resolve("../..").normalize();
    }

    private ArrayNode toArray(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private ObjectNode refMap(List<EvidenceItem> evidence,
                              java.util.function.Function<EvidenceItem, Integer> getter) {
        ObjectNode node = mapper.createObjectNode();
        for (EvidenceItem item : evidence) {
            Integer value = getter.apply(item);
            if (value == null) {
                node.putNull(item.getSourceRef());
            } else {
                node.put(item.getSourceRef(), value);
            }
        }
        return node;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }
}
