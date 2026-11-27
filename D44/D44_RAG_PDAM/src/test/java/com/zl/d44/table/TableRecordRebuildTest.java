package com.zl.d44.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d44.document.chunk.clean.TextCleaner;
import com.zl.d44.document.support.Sha256ContentHasher;
import com.zl.d44.document.table.PdfTableExtractor;
import com.zl.d44.document.table.TextTableExtractor;
import com.zl.d44.document.table.config.TableProperties;
import com.zl.d44.document.table.mapper.TableExtractMapper;
import com.zl.d44.document.table.mapper.TableRowMapper;
import com.zl.d44.document.table.po.TableExtractPO;
import com.zl.d44.document.table.po.TableRowPO;
import com.zl.d44.document.table.service.TableRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * R03 整改回归：PDF 重建（reExtract pdf=true）的 continuationOfTableId 重映射。
 *
 * <p>缺陷基线（整改前）：copyForRebuild 直拷旧 continuationOfTableId，
 * 旧表删除后新表引用悬空；idRemap 建而未用。</p>
 *
 * <p><b>Mockito 验证边界声明</b>（整改书 §5.3 允许，如实报告）：本类验证
 * "插入参数无旧 ID + patch 指向新 ID + 行归属/内容保真 + 异常不再续写"的
 * 服务层语义；数据库真实落库与悬空终态由在线核验（真实 MySQL 联查
 * continuation_of_table_id NOT IN 新 ID 集 = 0）补证，见
 * docs/evidence/D41/R01/tests/。</p>
 */
class TableRecordRebuildTest {

    private static final String DOC = "D44-REBUILD-T";
    private static final int VER = 3;

    private TableExtractMapper extractMapper;
    private TableRowMapper rowMapper;
    private TableRecordService service;

    /** insert 模拟自增：首表新 id=100 起。 */
    private final AtomicLong autoId = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        extractMapper = mock(TableExtractMapper.class);
        rowMapper = mock(TableRowMapper.class);
        Sha256ContentHasher hasher = mock(Sha256ContentHasher.class);
        when(hasher.hash(any(byte[].class))).thenReturn("h");
        service = new TableRecordService(new TableProperties(),
                mock(PdfTableExtractor.class), mock(TextTableExtractor.class),
                mock(TextCleaner.class), extractMapper, rowMapper, hasher,
                new ObjectMapper());
        autoId.set(100);
        when(extractMapper.insert(any(TableExtractPO.class))).thenAnswer(inv -> {
            TableExtractPO po = inv.getArgument(0);
            po.setId(autoId.getAndIncrement());
            return 1;
        });
    }

    private static TableExtractPO oldTable(long id, Long contOf) {
        TableExtractPO po = new TableExtractPO();
        po.setId(id);
        po.setDocumentId(DOC);
        po.setVersion(VER);
        po.setPageNumber(1);
        po.setTableIndex((int) (id % 10));
        po.setHeaderSnapshotJson("[\"H1\",\"H2\"]");
        po.setRowCount(1);
        po.setColCount(2);
        po.setDetectFlags("");
        po.setConfidence("HIGH");
        po.setContinuationOfTableId(contOf);
        po.setStatus("ACTIVE");
        return po;
    }

    private static TableRowPO oldRow(long tableId, int rowIndex, String cells) {
        TableRowPO row = new TableRowPO();
        row.setId(tableId * 100 + rowIndex);
        row.setTableId(tableId);
        row.setRowIndex(rowIndex);
        row.setCellsJson(cells);
        row.setSourceLineNumber(rowIndex * 2);
        row.setRecordHash("h");
        return row;
    }

    private List<TableExtractPO> stubRebuild(List<TableExtractPO> old,
                                             java.util.Map<Long, List<TableRowPO>> rows) {
        when(extractMapper.selectByDocumentVersion(DOC, VER)).thenReturn(old);
        for (TableExtractPO po : old) {
            when(rowMapper.selectByTableId(po.getId()))
                    .thenReturn(rows.getOrDefault(po.getId(), List.of()));
        }
        return old;
    }

    @Test
    @DisplayName("T1 两页续表：插入阶段无旧悬空 ID；重建后子表指向新父表 ID")
    void continuationRepointsToNewParentId() throws Exception {
        TableExtractPO parent = oldTable(10L, null);
        TableExtractPO child = oldTable(11L, 10L); // 旧子表→旧父表 10
        stubRebuild(List.of(parent, child), java.util.Map.of(
                10L, List.of(oldRow(10L, 1, "[c]")),
                11L, List.of(oldRow(11L, 1, "[c]"))));

        var result = service.reExtract(DOC, VER, List.of(), true);
        assertEquals(2, result.tableCount());

        ArgumentCaptor<TableExtractPO> ins = ArgumentCaptor.forClass(TableExtractPO.class);
        verify(extractMapper, times(2)).insert(ins.capture());
        // 缺陷断言点：新表插入时禁止携带旧表 ID（10/11 均已删除，携带=悬空）
        for (TableExtractPO fresh : ins.getAllValues()) {
            Long cont = fresh.getContinuationOfTableId();
            assertTrue(cont == null || cont >= 100,
                    "插入阶段 continuationOfTableId 不得为已删除旧 ID，实际=" + cont);
        }

        // 修复断言点：后段 patch 把子表(101)回链到新父表(100)
        ArgumentCaptor<TableExtractPO> patch = ArgumentCaptor.forClass(TableExtractPO.class);
        verify(extractMapper, atLeastOnce()).updateById(patch.capture());
        TableExtractPO childPatch = patch.getAllValues().stream()
                .filter(p -> Long.valueOf(101L).equals(p.getId())).findFirst().orElse(null);
        assertNotNull(childPatch, "子表重建后必须有 continuation 回链 patch");
        assertEquals(100L, childPatch.getContinuationOfTableId(),
                "续表引用必须指向本次重建产生的新父表 ID");
    }

    @Test
    @DisplayName("T2 非续表：重建后无回链 patch，continuation 保持空")
    void nonContinuationStaysNull() throws Exception {
        stubRebuild(List.of(oldTable(20L, null)),
                java.util.Map.of(20L, List.of(oldRow(20L, 1, "[c]"))));

        service.reExtract(DOC, VER, List.of(), true);

        ArgumentCaptor<TableExtractPO> ins = ArgumentCaptor.forClass(TableExtractPO.class);
        verify(extractMapper).insert(ins.capture());
        assertNull(ins.getValue().getContinuationOfTableId());
        verify(extractMapper, never()).updateById(any(TableExtractPO.class));
    }

    @Test
    @DisplayName("T3 映射缺失（旧父表不在本次重建集合）：不保留旧 ID，静默无回链")
    void missingMappingDropsDanglingId() throws Exception {
        stubRebuild(List.of(oldTable(30L, 999L)),
                java.util.Map.of(30L, List.of(oldRow(30L, 1, "[c]"))));

        service.reExtract(DOC, VER, List.of(), true);

        ArgumentCaptor<TableExtractPO> ins = ArgumentCaptor.forClass(TableExtractPO.class);
        verify(extractMapper).insert(ins.capture());
        assertNull(ins.getValue().getContinuationOfTableId(),
                "映射缺失时禁止保留悬空旧 ID(999)");
        verify(extractMapper, never()).updateById(any(TableExtractPO.class));
    }

    @Test
    @DisplayName("T4 数据行归属：全部行重挂新表 ID，行数/内容/recordHash 与重建前一致")
    void rowsReattachedToNewTableIdsIntact() throws Exception {
        stubRebuild(List.of(oldTable(10L, null), oldTable(11L, 10L)), java.util.Map.of(
                10L, List.of(oldRow(10L, 1, "[a]"), oldRow(10L, 2, "[b]")),
                11L, List.of(oldRow(11L, 1, "[c]"))));

        var result = service.reExtract(DOC, VER, List.of(), true);
        assertEquals(3, result.rowCount());

        ArgumentCaptor<TableRowPO> rowCap = ArgumentCaptor.forClass(TableRowPO.class);
        verify(rowMapper, times(3)).insert(rowCap.capture());
        List<TableRowPO> rows = rowCap.getAllValues();
        // 表序=旧表序（selectByDocumentVersion 稳定序），新 ID 100/101
        assertEquals(100L, rows.get(0).getTableId());
        assertEquals(100L, rows.get(1).getTableId());
        assertEquals(101L, rows.get(2).getTableId());
        assertEquals(List.of("[a]", "[b]", "[c]"),
                rows.stream().map(TableRowPO::getCellsJson).toList());
        assertTrue(rows.stream().allMatch(r -> "h".equals(r.getRecordHash())),
                "recordHash 保真");
        assertEquals(List.of(1, 2, 1), rows.stream().map(TableRowPO::getRowIndex).toList());
    }

    @Test
    @DisplayName("T5 异常路径：表插入失败上抛，不再写任何数据行（半套数据由事务原子性回滚，方法带 @Transactional）")
    void failureAbortsBeforeRowWrites() throws Exception {
        stubRebuild(List.of(oldTable(10L, null), oldTable(11L, 10L)), java.util.Map.of(
                10L, List.of(oldRow(10L, 1, "[a]")),
                11L, List.of(oldRow(11L, 1, "[c]"))));
        when(extractMapper.insert(any(TableExtractPO.class))).thenAnswer(inv -> {
            TableExtractPO po = inv.getArgument(0);
            if (po.getId() == null && autoId.get() == 101L) {
                throw new IllegalStateException("simulated insert failure");
            }
            po.setId(autoId.getAndIncrement());
            return 1;
        });

        assertThrows(IllegalStateException.class,
                () -> service.reExtract(DOC, VER, List.of(), true));
        verify(rowMapper, never()).insert(any(TableRowPO.class));

        // 事务声明静态核验（真实原子回滚由在线核验兜底——Mockito 边界内如实声明）
        var m = TableRecordService.class.getMethod("reExtract", String.class, int.class,
                List.class, boolean.class);
        var tx = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(tx, "reExtract 必须声明 @Transactional");
        assertArrayEquals(new Class[]{Exception.class}, tx.rollbackFor());
    }
}
