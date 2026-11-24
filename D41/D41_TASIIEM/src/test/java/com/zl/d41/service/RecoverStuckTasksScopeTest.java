package com.zl.d41.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d41.document.mapper.DocumentChunkIndexMapper;
import com.zl.d41.document.mapper.DocumentVectorIndexMapper;
import com.zl.d41.document.mapper.KnowledgeDocumentMapper;
import com.zl.d41.document.po.DocumentVectorIndexPO;
import com.zl.d41.document.sync.mapper.DocumentSyncMapper;
import com.zl.d41.document.sync.service.DocumentSyncService;
import com.zl.d41.document.vectorindex.config.VectorIndexProperties;
import com.zl.d41.document.vectorindex.dto.VectorIndexResultView;
import com.zl.d41.document.vectorindex.support.VectorIndexStateTx;
import com.zl.d41.document.vectorstore.PgVectorStoreProperties;
import com.zl.d41.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d41.document.vectorindex.service.VectorReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R01 整改单测：recoverStuckTasks 文档范围隔离（SQL 层 likeRight + 执行前防御终校验）
 * 与 VectorReconciliationService.applyCompensation 的 scope 传递。
 *
 * <p>任务书测试 A/B/C 的单元层对应；共享表内真实前缀隔离（d39-/d41- 假想行零触碰）
 * 的 DB 级实证见 docs\evidence\D41\repair\ 的 realdb harness 留痕。</p>
 */
class RecoverStuckTasksScopeTest {

    private static DocumentVectorIndexPO stuckRow(String documentId, int version, int attempt) {
        DocumentVectorIndexPO po = new DocumentVectorIndexPO();
        po.setDocumentId(documentId);
        po.setVersion(version);
        po.setStatus("PENDING");
        po.setStrategy("token");
        po.setAttemptCount(attempt);
        po.setUpdatedAt(LocalDateTime.now().minusHours(2));
        return po;
    }

    private DocumentVectorIndexService newService(DocumentVectorIndexMapper indexMapper,
                                                  DocumentChunkIndexMapper chunkMapper,
                                                  VectorIndexProperties properties) {
        return new DocumentVectorIndexService(
                mock(VectorStore.class), mock(JdbcTemplate.class),
                mock(PgVectorStoreProperties.class), indexMapper, chunkMapper,
                mock(com.zl.d41.document.vectorindex.support.StableChunkIdGenerator.class),
                mock(com.zl.d41.document.support.Sha256ContentHasher.class),
                properties, mock(VectorIndexStateTx.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(DocumentSyncService.class));
    }

    @Test
    @DisplayName("A：prefix=d41- 时查询必须携带 SQL 层前缀条件（likeRight，非仅内存过滤）")
    void scopedQueryUsesSqlPrefixFilter() {
        DocumentVectorIndexMapper indexMapper = mock(DocumentVectorIndexMapper.class);
        when(indexMapper.selectList(any())).thenReturn(List.of());
        VectorIndexProperties props = new VectorIndexProperties();
        DocumentVectorIndexService service = newService(indexMapper,
                mock(DocumentChunkIndexMapper.class), props);

        service.recoverStuckTasks("d41-");

        ArgumentCaptor<QueryWrapper<DocumentVectorIndexPO>> cap =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(indexMapper).selectList(cap.capture());
        QueryWrapper<DocumentVectorIndexPO> wrapper = cap.getValue();
        assertTrue(wrapper.getTargetSql().contains("document_id LIKE"),
                "范围过滤必须落在 SQL： " + wrapper.getTargetSql());
        assertTrue(wrapper.getParamNameValuePairs().containsValue("d41-%"),
                "likeRight 参数必须为前缀通配： " + wrapper.getParamNameValuePairs());
    }

    @Test
    @DisplayName("B：无参/空白 prefix 保留全量恢复语义（查询不含 document_id 条件）")
    void unscopedKeepsLegacyFullScan() {
        DocumentVectorIndexMapper indexMapper = mock(DocumentVectorIndexMapper.class);
        when(indexMapper.selectList(any())).thenReturn(List.of());
        DocumentVectorIndexService service = newService(indexMapper,
                mock(DocumentChunkIndexMapper.class), new VectorIndexProperties());

        List<VectorIndexResultView> r1 = service.recoverStuckTasks();
        List<VectorIndexResultView> r2 = service.recoverStuckTasks("  ");

        assertTrue(r1.isEmpty() && r2.isEmpty());
        ArgumentCaptor<QueryWrapper<DocumentVectorIndexPO>> cap =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(indexMapper, org.mockito.Mockito.times(2)).selectList(cap.capture());
        for (QueryWrapper<DocumentVectorIndexPO> w : cap.getAllValues()) {
            assertFalse(w.getTargetSql().contains("document_id"),
                    "全量语义不得注入范围条件： " + w.getTargetSql());
        }
    }

    @Test
    @DisplayName("A2 防御线：结果集中混入范围外行（过期读/脏数据）时，该行的快照读取与恢复管线零触达")
    void outOfScopeRowIsNeverTouched() {
        DocumentVectorIndexMapper indexMapper = mock(DocumentVectorIndexMapper.class);
        when(indexMapper.selectList(any())).thenReturn(List.of(
                stuckRow("d41-doc-a", 1, 0),
                stuckRow("DOC-SERVO-TROUBLE-002", 1, 0),
                stuckRow("d32eval-doc-x", 1, 0)));
        DocumentChunkIndexMapper chunkMapper = mock(DocumentChunkIndexMapper.class);
        when(chunkMapper.selectByDocumentVersion(anyString(), any())).thenReturn(List.of());
        DocumentVectorIndexService service = newService(indexMapper, chunkMapper,
                new VectorIndexProperties());

        service.recoverStuckTasks("d41-");

        // 范围外行：快照都不允许读（更谈不上重索引/改状态）
        verify(chunkMapper, never()).selectByDocumentVersion(eq("DOC-SERVO-TROUBLE-002"), any());
        verify(chunkMapper, never()).selectByDocumentVersion(eq("d32eval-doc-x"), any());
        // 范围内行正常进入处理（此处空快照走 NO_SNAPSHOT 分支，证明确实被执行侧评估）
        verify(chunkMapper).selectByDocumentVersion("d41-doc-a", 1);
    }

    @Test
    @DisplayName("C：applyCompensation 必须把本次 scope 传给恢复管线（reconcile(false,\"d41-\") 全链路）")
    void compensationPassesScopeToRecovery() {
        DocumentVectorIndexService indexService = mock(DocumentVectorIndexService.class);
        when(indexService.recoverStuckTasks(any())).thenReturn(List.of());
        VectorReconciliationService service = new VectorReconciliationService(
                mock(JdbcTemplate.class, RETURNS_DEFAULTS),
                mock(PgVectorStoreProperties.class),
                mock(DocumentVectorIndexMapper.class),
                mock(DocumentChunkIndexMapper.class),
                indexService,
                new VectorIndexProperties(),
                new ObjectMapper(),
                mock(KnowledgeDocumentMapper.class),
                mock(DocumentSyncMapper.class),
                mock(VectorIndexStateTx.class));

        service.reconcile(false, "d41-");

        verify(indexService).recoverStuckTasks("d41-");
        verify(indexService, never()).recoverStuckTasks();
        verify(indexService, never()).recoverStuckTasks((String) null);
    }

    @Test
    @DisplayName("C-空：scope 空串（无 prefix 全局修复）映射为全量恢复（null 透传，兼容原语义）")
    void emptyScopeMeansFullRecovery() {
        DocumentVectorIndexService indexService = mock(DocumentVectorIndexService.class);
        when(indexService.recoverStuckTasks(any())).thenReturn(List.of());
        VectorReconciliationService service = new VectorReconciliationService(
                mock(JdbcTemplate.class, RETURNS_DEFAULTS),
                mock(PgVectorStoreProperties.class),
                mock(DocumentVectorIndexMapper.class),
                mock(DocumentChunkIndexMapper.class),
                indexService,
                new VectorIndexProperties(),
                new ObjectMapper(),
                mock(KnowledgeDocumentMapper.class),
                mock(DocumentSyncMapper.class),
                mock(VectorIndexStateTx.class));

        service.reconcile(false, "");

        verify(indexService).recoverStuckTasks((String) null);
    }
}
