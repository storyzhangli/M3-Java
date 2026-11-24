package com.zl.d41.rag.validation;

import java.util.List;

/**
 * 生成后引用与事实支持校验结果。
 *
 * <p>刻意区分四类：</p>
 * <ul>
 *   <li>引用 ID 合法性：{@code [Sx]} 是否在白名单；</li>
 *   <li>Claim-Citation 关联：每个断言是否由其<b>自己标注的</b>引用支持；</li>
 *   <li>事实支持关系：参数断言是否在该引用对应的证据中找到同参数同值；</li>
 *   <li>虚构 URL：危险协议/本机路径或无来源支撑的 http(s) 链接。</li>
 * </ul>
 *
 * @param legalCitationPassed     引用标识是否全部合法
 * @param invalidCitations        非法/未知引用标识
 * @param unsupportedClaims       无支持或引用错配的确定性断言（参数=值）
 * @param fabricatedUrls          模型输出中的危险/虚构 URL
 * @param validationPassed        四类校验是否全部通过
 * @param claimCitationMismatches Claim-Citation 关联失败明细（含未标注与错配原因）
 */
public record CitationValidationResult(
        boolean legalCitationPassed,
        List<String> invalidCitations,
        List<String> unsupportedClaims,
        List<String> fabricatedUrls,
        boolean validationPassed,
        List<String> claimCitationMismatches) {

    /**
     * 向后兼容构造器（不含 Claim-Citation 明细），保留旧调用方签名。
     */
    public CitationValidationResult(boolean legalCitationPassed,
                                    List<String> invalidCitations,
                                    List<String> unsupportedClaims,
                                    List<String> fabricatedUrls,
                                    boolean validationPassed) {
        this(legalCitationPassed, invalidCitations, unsupportedClaims, fabricatedUrls,
                validationPassed, List.of());
    }

    /**
     * 向后兼容构造器（不含虚构 URL 字段），保留更早调用方签名。
     */
    public CitationValidationResult(boolean legalCitationPassed,
                                    List<String> invalidCitations,
                                    List<String> unsupportedClaims,
                                    boolean validationPassed) {
        this(legalCitationPassed, invalidCitations, unsupportedClaims, List.of(),
                validationPassed, List.of());
    }

    public static CitationValidationResult pass() {
        return new CitationValidationResult(true, List.of(), List.of(), List.of(),
                true, List.of());
    }
}
