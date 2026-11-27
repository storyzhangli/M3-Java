package com.zl.d44.rag.assessment;

import com.zl.d44.rag.assessment.FactClaim;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参数事实抽取器：把正文中“参数 + 数值”的结构化表达确定性地抽取为 {@link FactClaim}。
 *
 * <p><b>边界（如实说明）</b>：本抽取器只覆盖“已知参数标签 + 分隔符 + 数值”的结构化表达，
 * 不做通用自然语言理解；复杂句式可能抽不到，启发式脆弱性被刻意隔离在本类，便于单独测试与演进。</p>
 *
 * <p>参数标签集合为可扩展的默认集合，长标签优先匹配，避免“额定压力”被“压力”截断。</p>
 */
@Component
public class FactClaimExtractor {

    /** 已知参数标签（长在前），覆盖设备手册常见参数。 */
    private static final String PARAMETERS =
            "额定压力|额定流量|工作压力|工作温度|额定功率|额定转速|额定电压|额定电流|"
                    + "额定扬程|输出压力|排气压力|气蚀余量|压力|流量|温度|转速|电压|电流|功率|"
                    + "扬程|频率|气压|噪声|振动|电压等级";

    /** 参数标签 + 可选分隔符 + 数值（含小数）。 */
    private static final Pattern CLAIM_PATTERN = Pattern.compile(
            "(" + PARAMETERS + ")\\s*[:：为是]?\\s*(\\d+(?:\\.\\d+)?)");

    /**
     * 抽取事实声明。
     *
     * @param subject   主体（由调用方按文档标题/设备主题解析）
     * @param text      正文
     * @param sourceRef 来源标识
     * @return 事实声明列表（不为 null）
     */
    public List<FactClaim> extract(String subject, String text, String sourceRef) {
        List<FactClaim> claims = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return claims;
        }
        Matcher matcher = CLAIM_PATTERN.matcher(text);
        while (matcher.find()) {
            String parameter = matcher.group(1);
            String value = normalizeValue(matcher.group(2));
            claims.add(new FactClaim(subject, parameter, value, sourceRef));
        }
        return claims;
    }

    /** 数值归一化：去掉前导零（保留 0 与小数），保证 "0.80" 与 "0.8" 等价。 */
    private String normalizeValue(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.contains(".")) {
            String[] parts = raw.split("\\.", 2);
            String intPart = parts[0].isEmpty() ? "0" : parts[0].replaceFirst("^0+(?=\\d)", "");
            String frac = parts[1].replaceAll("0+$", "");
            return frac.isEmpty() ? intPart : intPart + "." + frac;
        }
        return raw.replaceFirst("^0+(?=\\d)", "");
    }
}
