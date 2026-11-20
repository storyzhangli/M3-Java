package com.zl.d37.rag.assessment;

/**
 * 从证据/回答中确定性抽取的参数事实声明。
 *
 * @param subject   主体（设备/文档主题，用于冲突范围限定）
 * @param parameter 参数名（如 额定压力）
 * @param value     参数值（归一化数值字符串）
 * @param sourceRef 来源标识（S1 等；回答侧抽取时可为 null）
 */
public record FactClaim(String subject, String parameter, String value, String sourceRef) {

    /**
     * 事实键：同主体同参数才会被比较。
     */
    public String factKey() {
        return subject + "|" + parameter;
    }
}
