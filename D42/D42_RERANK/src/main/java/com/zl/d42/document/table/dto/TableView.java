package com.zl.d42.document.table.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d42.document.table.po.TableExtractPO;
import com.zl.d42.document.table.po.TableRowPO;
import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表格记录对外视图（不暴露 PO 裸行；header/detectFlags 反序列化为结构）。
 */
@Data
public class TableView {

    private Long id;
    private String documentId;
    private Integer version;
    private Integer pageNumber;
    private Integer tableIndex;
    private List<String> header = new ArrayList<>();
    private Integer rowCount;
    private Integer colCount;
    private Integer sourceGlobalStart;
    private Integer sourceGlobalEnd;
    private Integer sourceLineStart;
    private Integer sourceLineEnd;
    private List<String> detectFlags = new ArrayList<>();
    private String confidence;
    private Long continuationOfTableId;
    private String status;

    /** PO → 视图（header_snapshot_json/detect_flags 解析）。 */
    public static TableView from(TableExtractPO po, ObjectMapper mapper) throws IOException {
        TableView v = new TableView();
        v.id = po.getId();
        v.documentId = po.getDocumentId();
        v.version = po.getVersion();
        v.pageNumber = po.getPageNumber();
        v.tableIndex = po.getTableIndex();
        v.header = mapper.readValue(po.getHeaderSnapshotJson(), new TypeReference<List<String>>() { });
        v.rowCount = po.getRowCount();
        v.colCount = po.getColCount();
        v.sourceGlobalStart = po.getSourceGlobalStart();
        v.sourceGlobalEnd = po.getSourceGlobalEnd();
        v.sourceLineStart = po.getSourceLineStart();
        v.sourceLineEnd = po.getSourceLineEnd();
        if (po.getDetectFlags() != null && !po.getDetectFlags().isBlank()) {
            v.detectFlags = List.of(po.getDetectFlags().split(","));
        }
        v.confidence = po.getConfidence();
        v.continuationOfTableId = po.getContinuationOfTableId();
        v.status = po.getStatus();
        return v;
    }

    /** 数据行视图。 */
    @Data
    public static class RowView {

        private Long id;
        private Long tableId;
        private Integer rowIndex;
        private String cellsJson;
        private Integer sourceLineNumber;
        private String recordHash;

        public static RowView from(TableRowPO po) {
            RowView v = new RowView();
            v.id = po.getId();
            v.tableId = po.getTableId();
            v.rowIndex = po.getRowIndex();
            v.cellsJson = po.getCellsJson();
            v.sourceLineNumber = po.getSourceLineNumber();
            v.recordHash = po.getRecordHash();
            return v;
        }
    }
}
