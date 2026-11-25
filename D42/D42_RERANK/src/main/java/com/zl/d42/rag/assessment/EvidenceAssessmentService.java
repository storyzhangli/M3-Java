package com.zl.d42.rag.assessment;

import com.zl.d42.device.ClarificationService;
import com.zl.d42.device.model.DeviceInformation;
import com.zl.d42.document.model.DocumentLifecycleStatus;
import com.zl.d42.rag.context.EvidenceItem;
import com.zl.d42.rag.model.RagErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
/**
 * 证据评估服务：在检索/组装之后、生成之前，确定性地评估证据充分性、低相关与冲突。
 *
 * <p><b>职责边界</b>：不调用 ChatModel、不触发检索；相似度仅作信号，不单独决定结论。</p>
 *
 * <p>决策优先级（可解释、可复现）：</p>
 * <ol>
 *   <li>生命周期筛选：DRAFT/DEPRECATED 拒绝；</li>
 *   <li>同主体同参数不同值 → CONFLICT；</li>
 *   <li>无有效证据 → REFUSE/NO_EVIDENCE；</li>
 *   <li>全部低相关 → REFUSE/LOW_RELEVANCE；</li>
 *   <li>跨设备主体且型号歧义 → CLARIFY（复用 {@link ClarificationService}）；</li>
 *   <li>术语覆盖不足 → REFUSE/INSUFFICIENT_EVIDENCE；否则 ANSWER。</li>
 * </ol>
 */
@Slf4j
@Service
public class EvidenceAssessmentService {

    /** 中文问题功能词/语气词，构建术语时剔除，避免“的、是、多少”拉低覆盖率。 */
    private static final Set<Character> QUESTION_STOP_CHARS = Set.of(
            '的', '是', '吗', '呢', '多', '少', '什', '么', '怎', '如', '何',
            '请', '问', '吧', '啊', '呀', '嘛', '呗', '喽');

    private final DocumentLifecyclePort lifecyclePort;
    private final FactClaimExtractor claimExtractor;
    private final EvidenceAssessmentProperties properties;
    private final ClarificationService clarificationService;

    public EvidenceAssessmentService(DocumentLifecyclePort lifecyclePort,
                                     FactClaimExtractor claimExtractor,
                                     EvidenceAssessmentProperties properties,
                                     ClarificationService clarificationService) {
        this.lifecyclePort = lifecyclePort;
        this.claimExtractor = claimExtractor;
        this.properties = properties;
        this.clarificationService = clarificationService;
    }

    /**
     * 执行证据评估。
     *
     * @param evidence 已组装证据（非空列表）
     * @param question 规范化问题
     * @return 评估结果（永不返回 null）
     */
    public EvidenceAssessmentResult assess(List<EvidenceItem> evidence, String question) {
        List<String> evidenceIds = new ArrayList<>();
        List<String> details = new ArrayList<>();
        if (evidence != null) {
            for (EvidenceItem item : evidence) {
                evidenceIds.add(item.getSourceRef());
            }
        }

        // 1) 生命周期筛选
        List<EvidenceItem> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<DocumentLifecycleStatus> rejectedStatuses = new ArrayList<>();
        if (evidence != null) {
            for (EvidenceItem item : evidence) {
                DocumentLifecycleStatus lifecycle = lifecyclePort.resolve(item.getDocumentId());
                item.setLifecycleStatus(lifecycle);
                if (lifecycle.isEffective()) {
                    accepted.add(item);
                    details.add(item.getSourceRef() + " 生命周期=" + lifecycle.name() + "，进入有效证据");
                } else {
                    rejected.add(item.getSourceRef());
                    rejectedStatuses.add(lifecycle);
                    details.add(item.getSourceRef() + " 生命周期=" + lifecycle.name()
                            + "，未生效/状态不可确认，筛除");
                }
            }
        }

        // 2) 事实抽取与冲突检测
        Map<String, Map<String, List<String>>> factValues = new LinkedHashMap<>();
        Map<String, String[]> factMeta = new LinkedHashMap<>();
        for (EvidenceItem item : accepted) {
            String subject = resolveSubject(item);
            for (FactClaim claim : claimExtractor.extract(
                    subject, item.getContent(), item.getSourceRef())) {
                factValues.computeIfAbsent(claim.factKey(), k -> new LinkedHashMap<>())
                        .computeIfAbsent(claim.value(), v -> new ArrayList<>())
                        .add(claim.sourceRef());
                factMeta.put(claim.factKey(), new String[]{subject, claim.parameter()});
            }
        }
        List<ConflictGroup> conflicts = buildConflictGroups(factValues, factMeta);
        for (ConflictGroup group : conflicts) {
            details.add("冲突：主体=" + group.subject() + " 参数=" + group.parameter()
                    + " 不同取值=" + group.values());
        }

        // 3) 决策优先级
        if (!conflicts.isEmpty()) {
            return build(AssessmentDecision.CONFLICT, RagErrorCode.EVIDENCE_CONFLICT,
                    "有效证据对同一参数存在冲突，需权威依据或人工确认，未按版本号一刀切。",
                    evidenceIds, accepted, rejected, conflicts, List.of(), details);
        }
        if (accepted.isEmpty()) {
            // 基础设施故障（查询失败）与业务上无有效证据明确区分，不静默放行、不隐藏故障
            if (rejectedStatuses.contains(DocumentLifecycleStatus.LOOKUP_FAILED)) {
                return build(AssessmentDecision.REFUSE, RagErrorCode.LIFECYCLE_LOOKUP_FAILED,
                        "生命周期查询失败，无法确认证据有效性，已停止生成，未调用大模型。",
                        evidenceIds, accepted, rejected, conflicts, List.of(), details);
            }
            return build(AssessmentDecision.REFUSE, RagErrorCode.NO_EVIDENCE,
                    "知识库中无有效证据，已停止生成，未调用大模型。",
                    evidenceIds, accepted, rejected, conflicts, List.of(), details);
        }
        if (allLowRelevance(accepted)) {
            return build(AssessmentDecision.REFUSE, RagErrorCode.LOW_RELEVANCE,
                    "检索结果仅关键词相似，不能支持该问题，不包装为答案。",
                    evidenceIds, accepted, rejected, conflicts, List.of(), details);
        }

        Set<String> subjects = collectSubjects(accepted);
        if (properties.isClarifyOnAmbiguousSubject()
                && subjects.size() >= properties.getAmbiguousSubjectCount()) {
            ClarificationService.ClarificationResult clarification =
                    clarificationService.evaluate(new DeviceInformation());
            List<String> missing = clarification.missingInformation();
            details.add("跨设备主体且型号歧义，复用 ClarificationService 生成单条澄清");
            return build(AssessmentDecision.CLARIFY, RagErrorCode.MISSING_REQUIRED_CONTEXT,
                    clarification.clarificationQuestion(),
                    evidenceIds, accepted, rejected, conflicts, missing, details);
        }

        double coverage = termCoverage(question, accepted);
        details.add("问题术语覆盖率=" + Math.round(coverage * 100) + "%（确定性字符口径，非 Token）");
        if (coverage < properties.getMinQuestionTermCoverage()) {
            return build(AssessmentDecision.REFUSE, RagErrorCode.INSUFFICIENT_EVIDENCE,
                    "有相关证据但对问题覆盖不足，无法形成完整结论。",
                    evidenceIds, accepted, rejected, conflicts, List.of(), details);
        }

        return build(AssessmentDecision.ANSWER, RagErrorCode.NONE,
                "证据充分，允许进入生成。",
                evidenceIds, accepted, rejected, conflicts, List.of(), details);
    }

    private List<ConflictGroup> buildConflictGroups(
            Map<String, Map<String, List<String>>> factValues,
            Map<String, String[]> factMeta) {
        List<ConflictGroup> groups = new ArrayList<>();
        factValues.forEach((factKey, valueMap) -> {
            if (valueMap.size() >= 2) {
                String[] meta = factMeta.get(factKey);
                List<String> refs = new ArrayList<>();
                valueMap.values().forEach(refs::addAll);
                groups.add(new ConflictGroup(meta[0], meta[1],
                        List.copyOf(valueMap.keySet()), List.copyOf(new LinkedHashSet<>(refs))));
            }
        });
        return groups;
    }

    private boolean allLowRelevance(List<EvidenceItem> accepted) {
        boolean anySignal = false;
        for (EvidenceItem item : accepted) {
            Double similarity = item.getSimilarity();
            if (similarity == null) {
                return false; // 无法判定时不做低相关阻断
            }
            anySignal = true;
            if (similarity >= properties.getLowRelevanceSimilarityThreshold()) {
                return false;
            }
        }
        return anySignal;
    }

    private Set<String> collectSubjects(List<EvidenceItem> accepted) {
        Set<String> subjects = new LinkedHashSet<>();
        for (EvidenceItem item : accepted) {
            subjects.add(resolveSubject(item));
        }
        return subjects;
    }

    /**
     * 术语覆盖率：问题术语（中文相邻二字组 + 字母数字串）在有效证据正文中出现的比例。
     */
    private double termCoverage(String question, List<EvidenceItem> accepted) {
        if (question == null || question.isBlank()) {
            return 1.0d;
        }
        StringBuilder corpus = new StringBuilder();
        for (EvidenceItem item : accepted) {
            corpus.append(item.getContent() == null ? "" : item.getContent());
        }
        Set<String> terms = questionTerms(question);
        if (terms.isEmpty()) {
            return 1.0d;
        }
        int hit = 0;
        for (String term : terms) {
            if (corpus.indexOf(term) >= 0) {
                hit++;
            }
        }
        return (double) hit / terms.size();
    }

    private Set<String> questionTerms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder cjk = new StringBuilder();
        StringBuilder alnum = new StringBuilder();
        for (int i = 0; i <= question.length(); i++) {
            boolean end = i == question.length();
            char ch = end ? 0 : question.charAt(i);
            boolean isCjk = !end && ch >= 0x4e00 && ch <= 0x9fa5;
            boolean isAlnum = !end && (Character.isLetterOrDigit(ch));
            if (isCjk && !QUESTION_STOP_CHARS.contains(ch)) {
                flushAlnum(alnum, terms);
                cjk.append(ch);
            } else if (isAlnum) {
                flushCjk(cjk, terms);
                alnum.append(ch);
            } else {
                flushCjk(cjk, terms);
                flushAlnum(alnum, terms);
            }
        }
        return terms;
    }

    private void flushCjk(StringBuilder cjk, Set<String> terms) {
        if (cjk.length() >= 2) {
            for (int i = 0; i < cjk.length() - 1; i++) {
                terms.add(cjk.substring(i, i + 2));
            }
        } else if (cjk.length() == 1) {
            terms.add(cjk.toString());
        }
        cjk.setLength(0);
    }

    private void flushAlnum(StringBuilder alnum, Set<String> terms) {
        if (alnum.length() > 0) {
            terms.add(alnum.toString());
        }
        alnum.setLength(0);
    }

    private String resolveSubject(EvidenceItem item) {
        String title = item.getTitle();
        return title == null || title.isBlank() ? "未知设备" : title.trim();
    }

    private EvidenceAssessmentResult build(AssessmentDecision decision, RagErrorCode code,
                                           String reason, List<String> evidenceIds,
                                           List<EvidenceItem> accepted, List<String> rejected,
                                           List<ConflictGroup> conflicts,
                                           List<String> missing, List<String> details) {
        List<String> acceptedIds = new ArrayList<>();
        for (EvidenceItem item : accepted) {
            acceptedIds.add(item.getSourceRef());
        }
        log.info("证据评估结束: decision={}, code={}, accepted={}, rejected={}",
                decision, code, acceptedIds.size(), rejected.size());
        return new EvidenceAssessmentResult(decision, code, reason,
                List.copyOf(evidenceIds), List.copyOf(acceptedIds), List.copyOf(rejected),
                List.copyOf(conflicts), List.copyOf(missing), List.copyOf(details));
    }
}
