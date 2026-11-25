package com.zl.d42.document.table.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 表格抽取记录表 {@code d42_table_extract} 的持久化对象（列口径=
 * {@code docs/temp_docs/D42/ddl/d42_schema.sql} 冻结 DDL，逐列对齐）。
 */
@Data
@NoArgsConstructor
@TableName("d42_table_extract")
public class TableExtractPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String documentId;

    private Integer version;

    private Integer pageNumber;

    private Integer tableIndex;

    /** JSON 数组：表头列名（续表匹配键）。 */
    private String headerSnapshotJson;

    private Integer rowCount;

    private Integer colCount;

    private Integer sourceGlobalStart;

    private Integer sourceGlobalEnd;

    private Integer sourceLineStart;

    private Integer sourceLineEnd;

    /** 逗号分隔退化/异常码（KP4 码表）。 */
    private String detectFlags;

    private String confidence;

    private Long continuationOfTableId;

    /** ACTIVE / ORPHANED（TABLE_ORPHAN 对账标记）。 */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
