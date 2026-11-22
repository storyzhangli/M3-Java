package com.zl.d39.rag.validation;

import com.zl.d39.rag.assessment.FactClaim;
import com.zl.d39.rag.assessment.FactClaimExtractor;
import com.zl.d39.rag.citation.UrlSafetyPolicy;
import com.zl.d39.rag.context.EvidenceItem;
import com.zl.d39.rag.validation.CitationValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成后校验服务：独立于 Prompt 的第二道确定性防线。
 *
 * <p><b>四类校验不可混为一谈</b>：</p>
 * <ol>
 *   <li>引用 ID 合法性：回答中的 {@code [Sx]} 是否都在本次来源白名单；</li>
 *   <li>Claim-Citation 关联：每个参数断言必须由<b>它自己标注的</b>引用支持。
 *       引用合法 ≠ 引用正确；事实在别处存在 ≠ 当前引用支持该事实；</li>
 *   <li>事实支持关系：参数/数值断言是否能在其标注引用的证据中找到同参数同值；</li>
 *   <li>虚构 URL：危险协议/本机路径，或不在证据 URL 白名单内的 http(s) 链接。</li>
 * </ol>
 *
 * <p><b>关联规则（确定性、保守）</b>：回答按句切分；句内以“引用标记组”为边界切块，
 * 每个文本块归属其后的引用标记组（连续标记视为一组，如 {@code [S1][S2]}）；
 * 句末残余文本沿用最近一组标记；整句无标记时，仅允许继承相邻“纯引用句”的标记。
 * 仍无法关联的断言按保守策略视为无支持，不默认已验证。</p>
 *
 * <p>本服务不调用模型、不访问数据库；启发式仅覆盖结构化参数断言与 URL 形态，
 * 复杂自然语言不在此列，也不把字符串匹配包装成语义证明。</p>
 */
@Slf4j
@Service
public class ResponseValidationService {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(S\\d+)]");

    /** 连续引用标记组（允许标记之间的空白）。 */
    private static final Pattern MARKER_RUN_PATTERN = Pattern.compile("(?:\\[S\\d+]\\s*)+");

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

    /** 回答侧断言抽取的主体占位（仅用于参数/数值匹配，不参与冲突判定）。 */
    private static final String ANSWER_SUBJECT = "ANSWER";

    private final FactClaimExtractor claimExtractor;

    public ResponseValidationService(FactClaimExtractor claimExtractor) {
        this.claimExtractor = claimExtractor;
    }

    /**
     * 校验回答。
     *
     * @param answer           模型原始回答
     * @param acceptedEvidence 评估接受且已通过响应前核验的有效证据
     * @return 校验结果（永不返回 null）
     */
    public CitationValidationResult validate(String answer, List<EvidenceItem> acceptedEvidence) {
        if (answer == null || answer.isBlank()) {
            return CitationValidationResult.pass();
        }

        Set<String> validRefs = new HashSet<>();
        Map<String, Set<String>> factsByRef = new LinkedHashMap<>();
        Set<String> allowedUrls = new HashSet<>();
        if (acceptedEvidence != null) {
            for (EvidenceItem item : acceptedEvidence) {
                String ref = item.getSourceRef();
                if (ref == null || ref.isBlank()) {
                    continue;
                }
                validRefs.add(ref);
                Set<String> facts = factsByRef.computeIfAbsent(ref, key -> new HashSet<>());
                String subject = item.getTitle() == null ? "未知设备" : item.getTitle();
                for (FactClaim claim : claimExtractor.extract(
                        subject, item.getContent(), ref)) {
                    facts.add(factKey(claim));
                }
                // 当前证据无 URL 字段，白名单为空；未来若证据携带可信 URL，在此经安全策略加入。
            }
        }

        List<String> invalidCitations = checkCitationLegality(answer, validRefs);
        List<String> unsupportedClaims = new ArrayList<>();
        List<String> claimCitationMismatches = new ArrayList<>();
        checkClaimCitationSupport(answer, validRefs, factsByRef,
                unsupportedClaims, claimCitationMismatches);
        List<String> fabricatedUrls = checkFabricatedUrls(answer, allowedUrls);

        boolean passed = invalidCitations.isEmpty() && unsupportedClaims.isEmpty()
                && claimCitationMismatches.isEmpty() && fabricatedUrls.isEmpty();
        log.info("生成后校验: citationLegal={}, unsupported={}, mismatch={}, fabricatedUrl={}, passed={}",
                invalidCitations.isEmpty(), unsupportedClaims.size(),
                claimCitationMismatches.size(), fabricatedUrls.size(), passed);
        return new CitationValidationResult(
                invalidCitations.isEmpty(),
                List.copyOf(invalidCitations),
                List.copyOf(unsupportedClaims),
                List.copyOf(fabricatedUrls),
                passed,
                List.copyOf(claimCitationMismatches));
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

    /**
     * 逐条验证 Claim 与其标注 Citation 的关联：只有该 Claim 自己的引用（或同组引用之一）
     * 支持它时才算通过；全局事实集合不再作为兜底依据。
     */
    private void checkClaimCitationSupport(String answer,
                                           Set<String> validRefs,
                                           Map<String, Set<String>> factsByRef,
                                           List<String> unsupportedClaims,
                                           List<String> claimCitationMismatches) {
        List<String> sentences = splitSentences(answer);
        Set<String> unsupported = new LinkedHashSet<>();
        Set<String> mismatches = new LinkedHashSet<>();

        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            if (isCitationOnlySentence(sentence)) {
                continue;
            }
            for (ClaimChunk chunk : splitByMarkerRuns(sentence)) {
                if (chunk.claims().isEmpty()) {
                    continue;
                }
                List<String> refs = chunk.refs();
                if (refs.isEmpty()) {
                    refs = neighborCitationRefs(sentences, i);
                }
                for (FactClaim claim : chunk.claims()) {
                    String key = factKey(claim);
                    boolean supported = false;
                    for (String ref : refs) {
                        if (validRefs.contains(ref)
                                && factsByRef.getOrDefault(ref, Set.of()).contains(key)) {
                            supported = true;
                            break;
                        }
                    }
                    if (supported) {
                        continue;
                    }
                    String claimText = claim.parameter() + "=" + claim.value();
                    unsupported.add(claimText);
                    if (refs.isEmpty()) {
                        mismatches.add(claimText + " 未标注引用，按保守策略视为无支持");
                    } else {
                        mismatches.add(claimText + " 不被 " + formatRefs(refs) + " 支持");
                    }
                }
            }
        }
        unsupportedClaims.addAll(unsupported);
        claimCitationMismatches.addAll(mismatches);
    }

    /** 按句末标点与换行切句，保留原字符，便于定位引用标记。 */
    private List<String> splitSentences(String answer) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < answer.length(); i++) {
            char c = answer.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                sentences.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    /**
     * 句内按“引用标记组”切块：文本块归属其后的标记组，句末残余沿用最近标记组。
     */
    private List<ClaimChunk> splitByMarkerRuns(String sentence) {
        List<ClaimChunk> chunks = new ArrayList<>();
        Matcher run = MARKER_RUN_PATTERN.matcher(sentence);
        int cursor = 0;
        List<String> lastRefs = List.of();
        while (run.find()) {
            String text = sentence.substring(cursor, run.end());
            List<String> refs = refsIn(run.group());
            chunks.add(buildChunk(text, refs));
            lastRefs = refs;
            cursor = run.end();
        }
        String tail = sentence.substring(cursor);
        if (!tail.isBlank()) {
            chunks.add(buildChunk(tail, lastRefs));
        }
        return chunks;
    }

    private ClaimChunk buildChunk(String text, List<String> refs) {
        return new ClaimChunk(text, refs, claimExtractor.extract(ANSWER_SUBJECT, text, null));
    }

    private List<String> refsIn(String text) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(text);
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        return List.copyOf(refs);
    }

    /** 纯引用句：去掉引用标记与标点后为空，其标记可被相邻断言句继承。 */
    private boolean isCitationOnlySentence(String sentence) {
        String stripped = CITATION_PATTERN.matcher(sentence).replaceAll("");
        return stripped.replaceAll("[\\s，,、.．:：;；。！？!?]", "").isEmpty();
    }

    /**
     * 只继承相邻连续“纯引用句”中的真实标记；空白句或纯标点句继续向前/向后查找，
     * 遇到含正文的句子立即停止（不跨句借用其他断言的引用）。
     */
    private List<String> neighborCitationRefs(List<String> sentences, int index) {
        for (int i = index - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            if (!isCitationOnlySentence(sentence)) {
                break;
            }
            List<String> refs = refsIn(sentence);
            if (!refs.isEmpty()) {
                return refs;
            }
        }
        for (int i = index + 1; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            if (!isCitationOnlySentence(sentence)) {
                break;
            }
            List<String> refs = refsIn(sentence);
            if (!refs.isEmpty()) {
                return refs;
            }
        }
        return List.of();
    }

    private String formatRefs(List<String> refs) {
        StringBuilder builder = new StringBuilder();
        for (String ref : refs) {
            builder.append('[').append(ref).append(']');
        }
        return builder.toString();
    }

    private String factKey(FactClaim claim) {
        return claim.parameter() + "|" + claim.value();
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

    /** 句内文本块及其归属引用、抽取出的事实断言。 */
    private record ClaimChunk(String text, List<String> refs, List<FactClaim> claims) {
    }
}
