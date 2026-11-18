package com.zl.d35.rag.validation;

import com.zl.d35.rag.assessment.FactClaim;
import com.zl.d35.rag.assessment.FactClaimExtractor;
import com.zl.d35.rag.context.EvidenceItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成后校验服务：独立于 Prompt 的第二道确定性防线。
 *
 * <p><b>两类校验不可混为一谈</b>：</p>
 * <ol>
 *   <li>引用 ID 合法性：回答中的 {@code [Sx]} 是否都在本次来源白名单；</li>
 *   <li>事实支持关系：回答中的参数/数值断言是否能在有效证据中找到同参数同值。
 *       引用格式合法 ≠ 事实有证据支持。</li>
 * </ol>
 *
 * <p>本服务不调用模型、不访问数据库；启发式仅覆盖结构化参数断言，复杂自然语言不在此列。</p>
 */
@Slf4j
@Service
public class ResponseValidationService {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    private final FactClaimExtractor claimExtractor;

    public ResponseValidationService(FactClaimExtractor claimExtractor) {
        this.claimExtractor = claimExtractor;
    }

    /**
     * 校验回答。
     *
     * @param answer           模型原始回答
     * @param acceptedEvidence 评估接受的有效证据
     * @return 校验结果（永不返回 null）
     */
    public CitationValidationResult validate(String answer, List<EvidenceItem> acceptedEvidence) {
        if (answer == null || answer.isBlank()) {
            return CitationValidationResult.pass();
        }

        Set<String> validRefs = new HashSet<>();
        Set<String> supportedFacts = new HashSet<>();
        if (acceptedEvidence != null) {
            for (EvidenceItem item : acceptedEvidence) {
                validRefs.add(item.getSourceRef());
                String subject = item.getTitle() == null ? "未知设备" : item.getTitle();
                for (FactClaim claim : claimExtractor.extract(
                        subject, item.getContent(), item.getSourceRef())) {
                    supportedFacts.add(claim.parameter() + "|" + claim.value());
                }
            }
        }

        List<String> invalidCitations = checkCitationLegality(answer, validRefs);
        List<String> unsupportedClaims = checkFactualSupport(answer, supportedFacts);

        boolean passed = invalidCitations.isEmpty() && unsupportedClaims.isEmpty();
        log.info("生成后校验: citationLegal={}, unsupported={}, passed={}",
                invalidCitations.isEmpty(), unsupportedClaims.size(), passed);
        return new CitationValidationResult(
                invalidCitations.isEmpty(),
                List.copyOf(invalidCitations),
                List.copyOf(unsupportedClaims),
                passed);
    }

    private List<String> checkCitationLegality(String answer, Set<String> validRefs) {
        List<String> invalid = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            String ref = matcher.group(1);
            if (!validRefs.contains(ref)) {
                invalid.add("[" + ref + "]");
            }
        }
        return invalid;
    }

    private List<String> checkFactualSupport(String answer, Set<String> supportedFacts) {
        List<String> unsupported = new ArrayList<>();
        List<FactClaim> answerClaims = claimExtractor.extract("ANSWER", answer, null);
        for (FactClaim claim : answerClaims) {
            String key = claim.parameter() + "|" + claim.value();
            if (!supportedFacts.contains(key)) {
                unsupported.add(claim.parameter() + "=" + claim.value());
            }
        }
        return unsupported;
    }
}
