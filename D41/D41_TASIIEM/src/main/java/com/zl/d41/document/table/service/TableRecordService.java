package com.zl.d41.document.table.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d41.document.chunk.clean.TextCleaner;
import com.zl.d41.document.chunk.model.CleanedDocument;
import com.zl.d41.document.chunk.model.TextBlock;
import com.zl.d41.document.model.DocumentPageContent;
import com.zl.d41.document.support.Sha256ContentHasher;
import com.zl.d41.document.table.PdfTableExtractor;
import com.zl.d41.document.table.TextTableExtractor;
import com.zl.d41.document.table.config.TableProperties;
import com.zl.d41.document.table.mapper.TableExtractMapper;
import com.zl.d41.document.table.mapper.TableRowMapper;
import com.zl.d41.document.table.model.DetectedTable;
import com.zl.d41.document.table.model.TablePatterns;
import com.zl.d41.document.table.po.TableExtractPO;
import com.zl.d41.document.table.po.TableRowPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * 表格结构化记录服务（D41 独立分支的唯一持久化入口）。
 *
 * <p>职责：调度双提取器 → 与正文 Chunk 同源的双轨定位（引用
 * {@link TextCleaner} 规范全文口径，零切分器改动，D5 裁决）→
 * 显式按 documentId+version 幂等清旧再插新（D40 教训：清理条件必须明确）→
 * 续表回链落库。{@code table.enabled=false} 时整体旁路（零表写，H1 回归口径）。</p>
 */
@Slf4j
@Service
public class TableRecordService {

    /** 持久化结果统计。 */
    public record PersistResult(int tableCount, int rowCount, boolean skipped) {
        public static final PersistResult SKIPPED = new PersistResult(0, 0, true);
    }

    private final TableProperties properties;
    private final PdfTableExtractor pdfExtractor;
    private final TextTableExtractor textExtractor;
    private final TextCleaner textCleaner;
    private final TableExtractMapper extractMapper;
    private final TableRowMapper rowMapper;
    private final Sha256ContentHasher hasher;
    private final ObjectMapper objectMapper;

    public TableRecordService(TableProperties properties,
                              PdfTableExtractor pdfExtractor,
                              TextTableExtractor textExtractor,
                              TextCleaner textCleaner,
                              TableExtractMapper extractMapper,
                              TableRowMapper rowMapper,
                              Sha256ContentHasher hasher,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.pdfExtractor = pdfExtractor;
        this.textExtractor = textExtractor;
        this.textCleaner = textCleaner;
        this.extractMapper = extractMapper;
        this.rowMapper = rowMapper;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    /**
     * 导入钩子入口。传播语义=<b>REQUIRES_NEW</b>（P2 裁决 D11，与任务书初读"同事务"的偏差留痕）：
     * 表格分支失败必须不连坐正文导入（独立分支解耦核心原则）；若外层正文事务后续回滚，
     * 遗留表行由 reconcile TABLE_ORPHAN 检出并标记 ORPHANED——分支间一致性由对账兜底
     * （D40 哲学：独立分支不设分布式事务）。
     *
     * @param pdfBytes PDF 原始字节（pdf=true 时必需；文本=null）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PersistResult extractAndPersist(String documentId, int version,
                                           List<DocumentPageContent> pages,
                                           byte[] pdfBytes, boolean pdf) throws IOException {
        if (!properties.isEnabled()) {
            return PersistResult.SKIPPED;
        }
        List<DetectedTable> tables;
        if (pdf) {
            PdfTableExtractor.Extraction extraction = pdfExtractor.extract(pdfBytes);
            tables = extraction.tables();
        } else {
            tables = new ArrayList<>();
            for (DocumentPageContent page : pages) {
                tables.addAll(textExtractor.extract(page.getPageNumber(), splitLines(page.getContent())));
            }
            PdfTableExtractor.linkContinuations(tables);
        }
        return persist(documentId, version, pages, tables);
    }

    /**
     * 重抽取端点语义（诚实能力边界，状态文件留痕）：
     * <ul>
     *   <li>文本类（txt/md）：页文本行可完全重放 → 真实重新解析；</li>
     *   <li>PDF：坐标路需要原始字节（导入时上下文），存储侧重抽取=
     *       <b>按已存结构化行幂等重建</b>（等值回插 + record_hash 复算校验一致），
     *       不伪造坐标重采。PDF 真重解析需重新上传原件。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public PersistResult reExtract(String documentId, int version,
                                   List<DocumentPageContent> pages, boolean pdf) throws IOException {
        if (!properties.isEnabled()) {
            return PersistResult.SKIPPED;
        }
        if (!pdf) {
            return extractAndPersist(documentId, version, pages, null, false);
        }
        List<TableExtractPO> old = extractMapper.selectByDocumentVersion(documentId, version);
        if (old.isEmpty()) {
            return new PersistResult(0, 0, false);
        }
        Map<Long, List<TableRowPO>> oldRows = new HashMap<>();
        int rowCount = 0;
        for (TableExtractPO po : old) {
            List<TableRowPO> rows = rowMapper.selectByTableId(po.getId());
            oldRows.put(po.getId(), rows);
            rowCount += rows.size();
        }
        LocalDateTime now = LocalDateTime.now();
        for (TableExtractPO po : old) {
            rowMapper.deleteByTableIds(List.of(po.getId()));
        }
        extractMapper.deleteByDocumentVersion(documentId, version);
        Map<Long, Long> idRemap = new HashMap<>();
        for (TableExtractPO po : old) {
            TableExtractPO fresh = copyForRebuild(po, now);
            extractMapper.insert(fresh);
            idRemap.put(po.getId(), fresh.getId());
        }
        for (TableExtractPO po : old) {
            Long newId = idRemap.get(po.getId());
            for (TableRowPO row : oldRows.get(po.getId())) {
                String recomputed = hasher.hash(row.getCellsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (!recomputed.equals(row.getRecordHash())) {
                    // H4 不静默：hash 漂移必须显式暴露（理论不可能：同源序列化）
                    log.error("重抽取 record_hash 漂移 table={} row={}", po.getId(), row.getRowIndex());
                }
                TableRowPO fresh = new TableRowPO();
                fresh.setTableId(newId);
                fresh.setRowIndex(row.getRowIndex());
                fresh.setCellsJson(row.getCellsJson());
                fresh.setSourceLineNumber(row.getSourceLineNumber());
                fresh.setRecordHash(row.getRecordHash());
                fresh.setCreatedAt(now);
                rowMapper.insert(fresh);
            }
        }
        return new PersistResult(old.size(), rowCount, false);
    }

    /** 对账/短路谓词用：文档全部版本的表格行数（跨版本口径，删除即全清）。 */
    public long countForDocument(String documentId) {
        return extractMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                .QueryWrapper<TableExtractPO>().eq("document_id", documentId));
    }

    /** 清理闭环（StateTx 删除事务调用）：删文档全部版本表记录+行，返回删除表数。
     *  行表无 document_id 列，按先取表 id 再级联删行（与 deleteForVersion 同型，范围由 table_id 界定）。 */
    @Transactional(rollbackFor = Exception.class)
    public int deleteAllForDocument(String documentId) {
        List<TableExtractPO> all = extractMapper.selectByDocumentId(documentId);
        if (all.isEmpty()) {
            return 0;
        }
        int rows = rowMapper.deleteByTableIds(
                all.stream().map(TableExtractPO::getId).toList());
        int deleted = extractMapper.deleteByDocumentId(documentId);
        log.info("表格记录清理 document={} tables={} rows={}", documentId, deleted, rows);
        return deleted;
    }

    /** 旧版本清理（StateTx deleteOldVersionRows 追加调用）：删指定 documentId+version 表记录+行。 */
    @Transactional(rollbackFor = Exception.class)
    public int deleteForVersion(String documentId, int version) {
        List<TableExtractPO> old = extractMapper.selectByDocumentVersion(documentId, version);
        if (old.isEmpty()) {
            return 0;
        }
        rowMapper.deleteByTableIds(old.stream().map(TableExtractPO::getId).toList());
        int deleted = extractMapper.deleteByDocumentVersion(documentId, version);
        log.info("表格记录版本清理 document={} version={} tables={}", documentId, version, deleted);
        return deleted;
    }

    // ================= 内部：定位 + 落库 =================

    private PersistResult persist(String documentId, int version,
                                  List<DocumentPageContent> pages,
                                  List<DetectedTable> tables) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        // 幂等清旧：显式 documentId+version 两步删（行→表），uk 兜并发
        List<TableExtractPO> old = extractMapper.selectByDocumentVersion(documentId, version);
        if (!old.isEmpty()) {
            rowMapper.deleteByTableIds(old.stream().map(TableExtractPO::getId).toList());
            extractMapper.deleteByDocumentVersion(documentId, version);
        }
        if (tables.isEmpty()) {
            return new PersistResult(0, 0, false);
        }

        // 双轨定位：TextCleaner 规范块 (page, normalizedText) → 有序队列，表行依序消费
        CleanedDocument cleaned = textCleaner.clean(pages);
        Map<String, LinkedList<TextBlock>> blockIndex = new HashMap<>();
        for (TextBlock block : cleaned.getBlocks()) {
            blockIndex.computeIfAbsent(block.getPageNumber() + "|" + block.getText(),
                    k -> new LinkedList<>()).add(block);
        }

        Map<Integer, Long> ordinalToId = new HashMap<>();
        Map<Integer, Integer> pageIndex = new HashMap<>();
        int totalRows = 0;
        for (int ordinal = 0; ordinal < tables.size(); ordinal++) {
            DetectedTable table = tables.get(ordinal);
            int tableIndex = pageIndex.merge(table.getPageNumber(), 1, Integer::sum);

            Integer globalStart = null;
            Integer globalEnd = null;
            for (String rowText : table.getRowTexts()) {
                LinkedList<TextBlock> queue = blockIndex
                        .get(table.getPageNumber() + "|" + TablePatterns.normalizeLine(rowText));
                TextBlock block = queue == null ? null : queue.poll();
                if (block != null) {
                    globalStart = globalStart == null ? block.getGlobalStart()
                            : Math.min(globalStart, block.getGlobalStart());
                    globalEnd = globalEnd == null ? block.getGlobalEnd()
                            : Math.max(globalEnd, block.getGlobalEnd());
                }
            }
            Integer lineStart = null;
            Integer lineEnd = null;
            for (Integer idx : table.getLineIndexes()) {
                if (idx != null && idx >= 0) {
                    lineStart = lineStart == null ? idx + 1 : Math.min(lineStart, idx + 1);
                    lineEnd = lineEnd == null ? idx + 1 : Math.max(lineEnd, idx + 1);
                }
            }

            TableExtractPO po = new TableExtractPO();
            po.setDocumentId(documentId);
            po.setVersion(version);
            po.setPageNumber(table.getPageNumber());
            po.setTableIndex(tableIndex);
            po.setHeaderSnapshotJson(objectMapper.writeValueAsString(table.getHeader()));
            po.setRowCount(table.getRows().size());
            po.setColCount(table.getColCount());
            po.setSourceGlobalStart(globalStart);
            po.setSourceGlobalEnd(globalEnd);
            po.setSourceLineStart(lineStart);
            po.setSourceLineEnd(lineEnd);
            po.setDetectFlags(String.join(",", table.getFlags()));
            po.setConfidence(table.getConfidence());
            po.setStatus("ACTIVE");
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            extractMapper.insert(po);
            ordinalToId.put(ordinal, po.getId());

            for (int r = 0; r < table.getRows().size(); r++) {
                String cellsJson = objectMapper.writeValueAsString(table.getRows().get(r));
                TableRowPO row = new TableRowPO();
                row.setTableId(po.getId());
                row.setRowIndex(r + 1);
                row.setCellsJson(cellsJson);
                row.setSourceLineNumber(r < table.getRowDataLineNumbers().size()
                        ? table.getRowDataLineNumbers().get(r) : null);
                row.setRecordHash(hasher.hash(cellsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                row.setCreatedAt(now);
                rowMapper.insert(row);
                totalRows++;
            }
        }
        // 续表回链：ordinal 定位 → 已插 id 覆写
        for (int ordinal = 0; ordinal < tables.size(); ordinal++) {
            DetectedTable table = tables.get(ordinal);
            if (table.isContinuation()) {
                Long previousId = ordinalToId.get(table.getContinuationOrdinal());
                if (previousId != null) {
                    TableExtractPO patch = new TableExtractPO();
                    patch.setId(ordinalToId.get(ordinal));
                    patch.setContinuationOfTableId(previousId);
                    extractMapper.updateById(patch);
                }
            }
        }
        return new PersistResult(tables.size(), totalRows, false);
    }

    private TableExtractPO copyForRebuild(TableExtractPO source, LocalDateTime now) {
        TableExtractPO fresh = new TableExtractPO();
        fresh.setDocumentId(source.getDocumentId());
        fresh.setVersion(source.getVersion());
        fresh.setPageNumber(source.getPageNumber());
        fresh.setTableIndex(source.getTableIndex());
        fresh.setHeaderSnapshotJson(source.getHeaderSnapshotJson());
        fresh.setRowCount(source.getRowCount());
        fresh.setColCount(source.getColCount());
        fresh.setSourceGlobalStart(source.getSourceGlobalStart());
        fresh.setSourceGlobalEnd(source.getSourceGlobalEnd());
        fresh.setSourceLineStart(source.getSourceLineStart());
        fresh.setSourceLineEnd(source.getSourceLineEnd());
        fresh.setDetectFlags(source.getDetectFlags());
        fresh.setConfidence(source.getConfidence());
        fresh.setContinuationOfTableId(source.getContinuationOfTableId());
        fresh.setStatus(source.getStatus());
        fresh.setCreatedAt(now);
        fresh.setUpdatedAt(now);
        return fresh;
    }

    static List<String> splitLines(String content) {
        if (content == null) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        return List.of(normalized.split("\n", -1));
    }

    // ================= 读面（Controller 经权限校验后调用） =================

    /** 表记录列表（指定 documentId+version，页/表序稳定）。 */
    public List<TableExtractPO> listTables(String documentId, int version) {
        return extractMapper.selectByDocumentVersion(documentId, version);
    }

    /** 表数据行（表序稳定）。 */
    public List<TableRowPO> listRows(Long tableId) {
        return rowMapper.selectByTableId(tableId);
    }

    /** 表记录存在性读取（rows 端点权限反查文档用）。 */
    public TableExtractPO findTable(Long tableId) {
        return extractMapper.selectById(tableId);
    }

    /** 命中行视图。 */
    public record QueryHit(Long tableId, int rowIndex, String column,
                           com.zl.d41.document.table.model.TableCell cell) {}

    /**
     * 列名+值精确命中查询（检索型消费入口）：按表头列名定位列，
     * value 与单元格 {@code value} 字段 trim 等值比较；缺格不命中。
     */
    public List<QueryHit> queryHits(String documentId, int version, String column,
                                    String value, int limit) throws IOException {
        List<QueryHit> hits = new ArrayList<>();
        String wantedColumn = column == null ? "" : column.trim();
        String wantedValue = value == null ? "" : value.trim();
        for (TableExtractPO table : listTables(documentId, version)) {
            List<String> header = objectMapper.readValue(table.getHeaderSnapshotJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            int colIndex = header.indexOf(wantedColumn);
            if (colIndex < 0) {
                continue;
            }
            for (TableRowPO row : listRows(table.getId())) {
                List<com.zl.d41.document.table.model.TableCell> cells = objectMapper.readValue(
                        row.getCellsJson(),
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class,
                                        com.zl.d41.document.table.model.TableCell.class));
                if (colIndex >= cells.size()) {
                    continue;
                }
                var cell = cells.get(colIndex);
                if (!cell.isNull() && cell.value() != null && cell.value().equals(wantedValue)) {
                    hits.add(new QueryHit(table.getId(), row.getRowIndex(), wantedColumn, cell));
                    if (hits.size() >= Math.max(1, limit)) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }
}
