package com.zl.d30.document.vectorstore.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 向量表统计视图。
 */
@Data
@AllArgsConstructor
public class VectorStatsView {

    /** 全限定表名。 */
    private String table;

    /** 当前行数。 */
    private long count;
}
