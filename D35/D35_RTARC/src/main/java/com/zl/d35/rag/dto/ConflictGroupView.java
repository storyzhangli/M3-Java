package com.zl.d35.rag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 冲突组对外视图：同一主体同一参数出现不同取值。
 */
@Data
public class ConflictGroupView {

    /** 主体（设备/文档主题）。 */
    private String subject;

    /** 参数名。 */
    private String parameter;

    /** 不同取值。 */
    private List<String> values = new ArrayList<>();

    /** 涉及的来源标识。 */
    private List<String> evidenceIds = new ArrayList<>();
}
