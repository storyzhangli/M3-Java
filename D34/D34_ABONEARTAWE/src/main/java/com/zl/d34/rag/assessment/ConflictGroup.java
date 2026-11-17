package com.zl.d34.rag.assessment;

import java.util.List;

/**
 * 冲突组：同一主体同一参数出现不同取值。
 *
 * @param subject     主体（设备/文档主题）
 * @param parameter   参数名
 * @param values      出现的不同取值（去重）
 * @param evidenceIds 涉及的来源标识
 */
public record ConflictGroup(String subject, String parameter,
                            List<String> values, List<String> evidenceIds) {
}
