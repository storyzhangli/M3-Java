package hybrideval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 失败标签集中定义：9 个一阶标签 + 细分原因码。
 * 每题可多标签；每类原因码至多一个；全部判定可由原始记录复算。
 * PAGE_LOCATION_MISSING 单列统计，不单独判失败（与生产契约一致）。
 */
public final class FailureLabels {

    public static final Set<String> LABELS = Set.of(
            "MISS_RECALL", "HIGH_NOISE", "FABRICATED_CITATION", "REFUSAL_ERROR",
            "WRONG_CLARIFICATION", "METADATA_INCOMPLETE", "TIMEOUT", "MODEL_ERROR", "RETRIEVAL_ERROR");

    /** 期望拒答时可接受的受控错误码（与 RagErrorCode 真实枚举一致）。 */
    public static final Set<String> REFUSE_CODES =
            Set.of("NO_EVIDENCE", "LOW_RELEVANCE", "INSUFFICIENT_EVIDENCE");

    public record Input(String expectedDecision, String actualDecision,
                        String retrievalStatus, String generationStatus, String errorCode,
                        String retrievalReasonCode,
                        int returnedCount, int relevantCount, boolean relevanceJudgeable,
                        boolean hasDocExpectation, boolean docCovered,
                        boolean hasChunkExpectation, boolean allChunksCovered,
                        boolean top1InExpected, boolean filterApplied, boolean mappingAmbiguous,
                        List<String> citationIssueCodes,
                        boolean metadataRequiredMissing, boolean pageMissing,
                        boolean timeoutRetrieval, boolean timeoutGeneration, boolean timeoutTotal,
                        boolean mustNotViolated, boolean decisionNotRun, boolean backendBlocked) {
    }

    public record Result(List<String> labels, boolean fail, String status) {
    }

    public static Result evaluate(Input in) {
        List<String> labels = new ArrayList<>();
        boolean fail = false;

        // 1) MISS_RECALL
        if (in.hasDocExpectation() && !in.docCovered()) {
            String code = in.returnedCount() == 0 ? "MISS_RECALL_EMPTY"
                    : in.filterApplied() ? "MISS_RECALL_FILTERED" : "MISS_RECALL_LOW_SCORE";
            labels.add("MISS_RECALL:" + code);
            fail = true;
        } else if (in.hasChunkExpectation() && !in.allChunksCovered()) {
            labels.add("MISS_RECALL:MISS_RECALL_LOW_SCORE");
            fail = true;
        }

        // 2) HIGH_NOISE
        if (in.relevanceJudgeable() && in.returnedCount() > 0) {
            double precision = (double) in.relevantCount() / in.returnedCount();
            if (precision < 0.5) {
                labels.add("HIGH_NOISE:HIGH_NOISE_PRECISION");
                fail = true;
            } else if (in.hasDocExpectation() && !in.top1InExpected()) {
                labels.add("HIGH_NOISE:HIGH_NOISE_TOP1");
                fail = true;
            }
        } else if (in.hasDocExpectation() && in.returnedCount() > 0 && !in.top1InExpected()) {
            labels.add("HIGH_NOISE:HIGH_NOISE_TOP1");
            fail = true;
        }

        // 3) FABRICATED_CITATION（静态结构校验问题码由调用方给出，每类至多一个）
        if (in.citationIssueCodes() != null && !in.citationIssueCodes().isEmpty()) {
            labels.add("FABRICATED_CITATION:" + in.citationIssueCodes().get(0));
            fail = true;
        }

        // 4) REFUSAL_ERROR（决策可判时）
        if (!in.decisionNotRun() && in.actualDecision() != null) {
            if ("ANSWER".equals(in.expectedDecision()) && "REFUSE".equals(in.actualDecision())) {
                labels.add("REFUSAL_ERROR:REFUSAL_FALSE_NEGATIVE");
                fail = true;
            } else if ("REFUSE".equals(in.expectedDecision()) && "ANSWER".equals(in.actualDecision())) {
                labels.add("REFUSAL_ERROR:REFUSAL_FALSE_POSITIVE");
                fail = true;
            } else if ("REFUSE".equals(in.expectedDecision()) && "REFUSE".equals(in.actualDecision())
                    && !REFUSE_CODES.contains(in.errorCode())) {
                labels.add("REFUSAL_ERROR:REFUSAL_WRONG_CODE");
                fail = true;
            }
            if (in.mustNotViolated() && "REFUSE".equals(in.expectedDecision())
                    && labels.stream().noneMatch(l -> l.startsWith("REFUSAL_ERROR"))) {
                labels.add("REFUSAL_ERROR:REFUSAL_FALSE_POSITIVE");
                fail = true;
            }
        }

        // 5) WRONG_CLARIFICATION
        if (!in.decisionNotRun() && in.actualDecision() != null) {
            if ("CLARIFY".equals(in.expectedDecision()) && !"CLARIFY".equals(in.actualDecision())) {
                labels.add("WRONG_CLARIFICATION:CLARIFY_EXPECTED_MISSING");
                fail = true;
            } else if (!"CLARIFY".equals(in.expectedDecision()) && "CLARIFY".equals(in.actualDecision())) {
                labels.add("WRONG_CLARIFICATION:CLARIFY_UNEXPECTED");
                fail = true;
            }
        }

        // 6) METADATA_INCOMPLETE（page 缺失仅统计）
        if (in.metadataRequiredMissing()) {
            labels.add("METADATA_INCOMPLETE:METADATA_REQUIRED_MISSING");
            fail = true;
        }
        if (in.pageMissing()) {
            labels.add("METADATA_INCOMPLETE:PAGE_LOCATION_MISSING");
        }

        // 7) TIMEOUT（按阶段）
        if (in.timeoutRetrieval()) {
            labels.add("TIMEOUT:TIMEOUT_RETRIEVAL");
            fail = true;
        }
        if (in.timeoutGeneration()) {
            labels.add("TIMEOUT:TIMEOUT_GENERATION");
            fail = true;
        }
        if (in.timeoutTotal()) {
            labels.add("TIMEOUT:TIMEOUT_TOTAL");
            fail = true;
        }

        // 8) MODEL_ERROR（NOT_CONFIGURED 是离线受控态，不是错误）
        if ("FAILED_ERROR".equals(in.generationStatus())) {
            String code = "EMPTY_RESPONSE".equals(in.errorCode()) ? "MODEL_EMPTY" : "MODEL_ERROR_GENERIC";
            labels.add("MODEL_ERROR:" + code);
            fail = true;
        }
        if ("FAILED_TIMEOUT".equals(in.generationStatus()) && !in.timeoutGeneration()) {
            labels.add("TIMEOUT:TIMEOUT_GENERATION");
            fail = true;
        }

        // 9) RETRIEVAL_ERROR（与空结果严格区分）
        if ("FAILED".equals(in.retrievalStatus())) {
            String code = "RETRIEVAL_EMBED_FAIL".equals(in.retrievalReasonCode())
                    ? "RETRIEVAL_EMBED_FAIL" : "RETRIEVAL_BACKEND_FAIL";
            labels.add("RETRIEVAL_ERROR:" + code);
            fail = true;
        }
        if (in.mappingAmbiguous()) {
            labels.add("RETRIEVAL_ERROR:RETRIEVAL_BACKEND_FAIL");
            fail = true;
        }

        String status;
        if (in.backendBlocked()) {
            status = "BLOCKED";
        } else if (fail) {
            status = "FAIL";
        } else if (in.decisionNotRun() && !in.hasDocExpectation() && !in.hasChunkExpectation()
                && !in.relevanceJudgeable()) {
            status = "NOT_RUN";
        } else {
            status = "PASS";
        }
        return new Result(List.copyOf(labels), fail, status);
    }

    private FailureLabels() {
    }
}
