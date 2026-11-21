package hybrideval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定问题集 JSONL v1.0 加载与 schema 校验（先冻结后评测）。
 * 核心契约为显式字段，不使用无约束 Map 承载业务字段。
 */
public final class Dataset {

    public static final Set<String> SCENARIOS =
            Set.of("ANSWERABLE", "UNANSWERABLE", "TERMINOLOGY", "BOUNDARY_FUZZY");
    public static final Set<String> DECISIONS = Set.of("ANSWER", "REFUSE", "CLARIFY", "CONFLICT");

    public record Question(String questionId, String scenario, String question,
                           String expectedDecision, List<String> expectedDocumentIds,
                           List<String> expectedChunkIds, List<String> mustNotReturnDocumentIds,
                           List<String> expectedTerms, List<String> missingInformation,
                           String annotatedAt, String annotator, String notes) {
    }

    private final List<Question> questions;

    private Dataset(List<Question> questions) {
        this.questions = List.copyOf(questions);
    }

    public List<Question> questions() {
        return questions;
    }

    public static Dataset loadAndValidate(Path path, ObjectMapper mapper) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Question> questions = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>();
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode n;
            try {
                n = mapper.readTree(line);
            } catch (IOException ex) {
                throw new IllegalStateException("第 " + lineNo + " 行非法 JSON，拒绝加载");
            }
            Question q = parse(n, lineNo);
            if (!ids.add(q.questionId())) {
                throw new IllegalStateException("questionId 重复: " + q.questionId());
            }
            questions.add(q);
        }
        validateShape(questions);
        return new Dataset(questions);
    }

    private static Question parse(JsonNode n, int lineNo) {
        String id = required(n, "questionId", lineNo);
        String scenario = required(n, "scenario", lineNo);
        String question = required(n, "question", lineNo);
        String decision = required(n, "expectedDecision", lineNo);
        if (!SCENARIOS.contains(scenario)) {
            throw new IllegalStateException("第 " + lineNo + " 行 scenario 非法: " + scenario);
        }
        if (!DECISIONS.contains(decision)) {
            throw new IllegalStateException("第 " + lineNo + " 行 expectedDecision 非法: " + decision);
        }
        if (question.length() > 2000) {
            throw new IllegalStateException("第 " + lineNo + " 行 question 超长");
        }
        return new Question(id, scenario, question, decision,
                strings(n, "expectedDocumentIds"), strings(n, "expectedChunkIds"),
                strings(n, "mustNotReturnDocumentIds"), strings(n, "expectedTerms"),
                strings(n, "missingInformation"),
                n.path("annotatedAt").asText(null), required(n, "annotator", lineNo),
                required(n, "notes", lineNo));
    }

    private static void validateShape(List<Question> questions) {
        if (questions.size() != 20) {
            throw new IllegalStateException("问题集必须为 20 问，实际 " + questions.size());
        }
        Map<String, Integer> perScenario = new LinkedHashMap<>();
        for (String s : List.of("ANSWERABLE", "UNANSWERABLE", "TERMINOLOGY", "BOUNDARY_FUZZY")) {
            perScenario.put(s, 0);
        }
        int injection = 0;
        for (Question q : questions) {
            perScenario.merge(q.scenario(), 1, Integer::sum);
            if (q.notes().contains("注入用例")) {
                injection++;
            }
            String prefix = switch (q.scenario()) {
                case "ANSWERABLE" -> "QA-";
                case "UNANSWERABLE" -> "QU-";
                case "TERMINOLOGY" -> "QT-";
                default -> "QF-";
            };
            if (!q.questionId().startsWith(prefix)) {
                throw new IllegalStateException("questionId 前缀与场景不符: " + q.questionId());
            }
            if (q.annotatedAt() == null || q.annotatedAt().isBlank()) {
                throw new IllegalStateException("问题集未冻结（annotatedAt 为空）: " + q.questionId());
            }
        }
        for (var e : perScenario.entrySet()) {
            if (e.getValue() != 5) {
                throw new IllegalStateException("场景 " + e.getKey() + " 必须 5 问，实际 " + e.getValue());
            }
        }
        if (injection < 2) {
            throw new IllegalStateException("注入用例不足 2 条，实际 " + injection);
        }
    }

    private static String required(JsonNode n, String field, int lineNo) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new IllegalStateException("第 " + lineNo + " 行缺少必填字段 " + field);
        }
        return v.asText();
    }

    private static List<String> strings(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return List.of();
        }
        if (!v.isArray()) {
            throw new IllegalStateException(field + " 必须为数组");
        }
        List<String> out = new ArrayList<>();
        v.forEach(x -> out.add(x.asText()));
        return List.copyOf(out);
    }
}
