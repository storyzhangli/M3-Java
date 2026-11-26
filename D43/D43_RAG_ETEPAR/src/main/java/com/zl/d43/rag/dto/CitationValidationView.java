package com.zl.d43.rag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成后引用校验对外视图：区分引用 ID 合法性与事实支持关系。
 */
@Data
public class CitationValidationView {

    /** 引用标识是否全部合法（在本次来源白名单内）。 */
    private boolean legalCitationPassed;

    /** 非法/未知引用标识。 */
    private List<String> invalidCitations = new ArrayList<>();

    /** 无证据支持的确定性断言。 */
    private List<String> unsupportedClaims = new ArrayList<>();

    /** Claim-Citation 关联失败明细：未标注引用或引用不支持该断言。 */
    private List<String> claimCitationMismatches = new ArrayList<>();

    /** 危险协议/本机路径或无来源支撑的虚构 URL。 */
    private List<String> fabricatedUrls = new ArrayList<>();

    /** 三类校验是否全部通过。 */
    private boolean validationPassed;
}
