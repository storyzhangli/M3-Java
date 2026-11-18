package com.zl.d35.rag.validation;

import com.zl.d35.rag.assessment.FactClaim;
import com.zl.d35.rag.assessment.FactClaimExtractor;
import com.zl.d35.rag.citation.UrlSafetyPolicy;
import com.zl.d35.rag.context.EvidenceItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成后校验服务：独立于 Prompt 的第二道确定性防线。
 *
 * <p><b>三类校验不可混为一谈</b>：</p>
 * <ol>
 *   <li>引用 ID 合法性：回答中的 {@code [Sx]} 是否都在本次来源白名单；</li>
 *   <li>事实支持关系：回答中的参数/数值断言是否能在有效证据中找到同参数同值。
 *       引用格式合法 ≠ 事实有证据支持；</li>
 *   <li>虚构 URL：危险协议/本机路径，或不在证据 URL 白名单内的 http(s) 链接。</li>
 * </ol>
 *
 * <p>本服务不调用模型、不访问数据库；启发式仅覆盖结构化参数断言与 URL 形态，
 * 复杂自然语言不在此列。</p>
 */
@Slf4j
@Service
public class ResponseValidationService {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    /** 任意 scheme:// 形态的链接 token。 */
    private static final Pattern SCHEME_URL_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^\\s<>\"']*");

    /**
     * 危险协议的单冒号形态（不要求 //），如 {@code javascript:alert(1)}、{@code data:...}。
     * 这类写法不会被 {@link #SCHEME_URL_PATTERN} 命中，需要单独拦截。
     */
    private static final Pattern DANGEROUS_COLON_SCHEME_PATTERN = Pattern.compile(
            "(?:javascript|data|vbscript|file|ftp):[^\\s<>\"']*",
            Pattern.CASE_INSENSITIVE);

    /** Windows 本机绝对路径（如 C:\Users\...）。 */
    private static final Pattern WINDOWS_PATH_PATTERN =
            Pattern.compile("[A-Za-z]:\\\\[^\\s<>\"']*");

    /** 类 Unix 本机敏感路径。 */
    private static final Pattern UNIX_PATH_PATTERN =
            Pattern.compile("/(?:home|Users|root|etc|var|tmp)/[^\\s<>\"']*");

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
        Set<String> allowedUrls = new HashSet<>();
        if (acceptedEvidence != null) {
            for (EvidenceItem item : acceptedEvidence) {
                validRefs.add(item.getSourceRef());
                String subject = item.getTitle() == null ? "未知设备" : item.getTitle();
                for (FactClaim claim : claimExtractor.extract(
                        subject, item.getContent(), item.getSourceRef())) {
                    supportedFacts.add(claim.parameter() + "|" + claim.value());
                }
                // 当前证据无 URL 字段，白名单为空；未来若证据携带可信 URL，在此经安全策略加入。
            }
        }

        List<String> invalidCitations = checkCitationLegality(answer, validRefs);
        List<String> unsupportedClaims = checkFactualSupport(answer, supportedFacts);
        List<String> fabricatedUrls = checkFabricatedUrls(answer, allowedUrls);

        boolean passed = invalidCitations.isEmpty() && unsupportedClaims.isEmpty()
                && fabricatedUrls.isEmpty();
        log.info("生成后校验: citationLegal={}, unsupported={}, fabricatedUrl={}, passed={}",
                invalidCitations.isEmpty(), unsupportedClaims.size(),
                fabricatedUrls.size(), passed);
        return new CitationValidationResult(
                invalidCitations.isEmpty(),
                List.copyOf(invalidCitations),
                List.copyOf(unsupportedClaims),
                List.copyOf(fabricatedUrls),
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

    /**
     * 虚构 URL 检测：危险协议、本机路径一律拦截；http(s) 链接必须在证据白名单内，
     * 否则视为无来源支撑的虚构 URL。
     */
    private List<String> checkFabricatedUrls(String answer, Set<String> allowedUrls) {
        Set<String> fabricated = new LinkedHashSet<>();

        // 单冒号危险协议（javascript:/data:/vbscript:/file:/ftp:）先拦截
        Matcher dangerousColonMatcher = DANGEROUS_COLON_SCHEME_PATTERN.matcher(answer);
        while (dangerousColonMatcher.find()) {
            fabricated.add(dangerousColonMatcher.group());
        }

        Matcher schemeMatcher = SCHEME_URL_PATTERN.matcher(answer);
        while (schemeMatcher.find()) {
            String token = schemeMatcher.group();
            if (UrlSafetyPolicy.isDangerous(token)) {
                fabricated.add(token);
            } else if (!UrlSafetyPolicy.isSafeUrl(token) || !allowedUrls.contains(token)) {
                // 非 http(s) 白名单协议，或 http(s) 但无证据来源
                fabricated.add(token);
            }
        }

        Matcher windowsMatcher = WINDOWS_PATH_PATTERN.matcher(answer);
        while (windowsMatcher.find()) {
            fabricated.add(windowsMatcher.group());
        }
        Matcher unixMatcher = UNIX_PATH_PATTERN.matcher(answer);
        while (unixMatcher.find()) {
            fabricated.add(unixMatcher.group());
        }
        return List.copyOf(fabricated);
    }
}

