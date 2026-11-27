package com.zl.d44.document.table.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 表格数据行表 {@code d44_table_row} 的持久化对象（列口径=冻结 DDL）。
 */
@Data
@NoArgsConstructor
@TableName("d44_table_row")
public class TableRowPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tableId;

    /** 数据行序 1-based（表头不占位）。 */
    private Integer rowIndex;

    /** JSON 数组：按表头列序的 [{value,unit,type,isNull}]。 */
    private String cellsJson;

    /** 来源行号（页文本 1-based）；未对齐为 null。 */
    private Integer sourceLineNumber;

    /** 规范化 cells 序列化 SHA-256（幂等/对账键）。 */
    private String recordHash;

    private LocalDateTime createdAt;
}
