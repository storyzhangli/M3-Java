package com.zl.d35.rag.citation;

import com.zl.d35.document.mapper.DocumentChunkIndexMapper;
import com.zl.d35.document.mapper.KnowledgeDocumentMapper;
import com.zl.d35.document.po.DocumentChunkIndexPO;
import com.zl.d35.document.po.KnowledgeDocumentPO;
import com.zl.d35.rag.context.EvidenceItem;
import com.zl.d35.rag.model.RagErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CitationVerificationService} 单元测试：批量核验、删除/失效分类、Chunk-Document
 * 归属校验（P1-01 T01–T06）与 cited 标记，并断言只执行两条批量 IN 查询，不产生 N+1。
 */
@ExtendWith(MockitoExtension.class)
class CitationVerificationServiceTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;

    @Mock
    private DocumentChunkIndexMapper chunkMapper;

    @InjectMocks
    private CitationVerificationService service;

    @Test
    @DisplayName("T01 归属一致：3 条来源全部保留，两个 Mapper 各调用一次（无 N+1）")
    void allValidBatchQueries() {
        List<EvidenceItem> evidence = evidence(3);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(3, "ACTIVE"));
        when(chunkMapper.selectList(any())).thenReturn(chunkPOs(3));

        var result = service.verify(evidence);

        assertEquals(3, result.validItems().size());
        assertFalse(result.hasRemoval());
        verify(documentMapper, times(1)).selectList(any());
        verify(chunkMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("T02 Document 存在但 Chunk 不存在：CITATION_TARGET_DELETED")
    void chunkDeleted() {
        List<EvidenceItem> evidence = evidence(2);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(2, "ACTIVE"));
        when(chunkMapper.selectList(any())).thenReturn(chunkPOs(1));

        var result = service.verify(evidence);

        assertEquals(1, result.validItems().size());
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED,
                result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("T03 文档与 Chunk 都存在但归属不一致：CITATION_OWNERSHIP_MISMATCH")
    void chunkOwnershipMismatchRejected() {
        List<EvidenceItem> evidence = evidence(2);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(2, "ACTIVE"));
        List<DocumentChunkIndexPO> chunks = new ArrayList<>();
        chunks.add(chunk("chunk-1", "doc-1"));
        chunks.add(chunk("chunk-2", "doc-other"));
        when(chunkMapper.selectList(any())).thenReturn(chunks);

        var result = service.verify(evidence);

        assertEquals(1, result.validItems().size());
        assertEquals("S1", result.validItems().get(0).getSourceRef());
        assertEquals(1, result.removed().size());
        assertEquals("S2", result.removed().get(0).sourceRef());
        assertEquals(RagErrorCode.CITATION_OWNERSHIP_MISMATCH,
                result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("T04 多来源中仅一个归属错误：只剔除该来源，其余保持有效")
    void partialOwnershipMismatch() {
        List<EvidenceItem> evidence = evidence(3);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(3, "ACTIVE"));
        List<DocumentChunkIndexPO> chunks = new ArrayList<>();
        chunks.add(chunk("chunk-1", "doc-1"));
        chunks.add(chunk("chunk-2", "doc-3"));
        chunks.add(chunk("chunk-3", "doc-3"));
        when(chunkMapper.selectList(any())).thenReturn(chunks);

        var result = service.verify(evidence);

        assertEquals(2, result.validItems().size());
        assertEquals(1, result.removed().size());
        assertEquals("S2", result.removed().get(0).sourceRef());
        assertEquals(RagErrorCode.CITATION_OWNERSHIP_MISMATCH,
                result.removed().get(0).reasonCode());
        assertEquals("S1", result.validItems().get(0).getSourceRef());
        assertEquals("S3", result.validItems().get(1).getSourceRef());
    }

    @Test
    @DisplayName("T05 Chunk 行 documentId 缺失：归属无法确认，拒绝")
    void chunkOwnerMissingRejected() {
        List<EvidenceItem> evidence = evidence(1);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(1, "ACTIVE"));
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("chunk-1", null)));

        var result = service.verify(evidence);

        assertTrue(result.validItems().isEmpty());
        assertEquals(RagErrorCode.CITATION_OWNERSHIP_MISMATCH,
                result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("T05b 同 chunkId 出现多个归属：归属歧义，拒绝")
    void duplicatedChunkOwnershipRejected() {
        List<EvidenceItem> evidence = evidence(1);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(1, "ACTIVE"));
        List<DocumentChunkIndexPO> chunks = new ArrayList<>();
        chunks.add(chunk("chunk-1", "doc-1"));
        chunks.add(chunk("chunk-1", "doc-other"));
        when(chunkMapper.selectList(any())).thenReturn(chunks);

        var result = service.verify(evidence);

        assertTrue(result.validItems().isEmpty());
        assertEquals(RagErrorCode.CITATION_OWNERSHIP_MISMATCH,
                result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("T06 批量查询多个 Chunk：正确映射归属且只查一次")
    void batchOwnershipMapping() {
        List<EvidenceItem> evidence = evidence(5);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(5, "ACTIVE"));
        when(chunkMapper.selectList(any())).thenReturn(chunkPOs(5));

        var result = service.verify(evidence);

        assertEquals(5, result.validItems().size());
        assertTrue(result.removed().isEmpty());
        verify(documentMapper, times(1)).selectList(any());
        verify(chunkMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("文档不存在：CITATION_TARGET_DELETED 并剔除")
    void documentDeleted() {
        List<EvidenceItem> evidence = evidence(2);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(1, "ACTIVE"));
        when(chunkMapper.selectList(any())).thenReturn(chunkPOs(2));

        var result = service.verify(evidence);

        assertEquals(1, result.validItems().size());
        assertEquals(1, result.removed().size());
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED,
                result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("文档 DEPRECATED：EVIDENCE_NOT_EFFECTIVE（D34 fail-closed 保持）")
    void deprecatedNotEffective() {
        List<EvidenceItem> evidence = evidence(1);
        when(documentMapper.selectList(any())).thenReturn(documentPOs(1, "DEPRECATED"));
        when(chunkMapper.selectList(any())).thenReturn(chunkPOs(1));

        var result = service.verify(evidence);

        assertTrue(result.validItems().isEmpty());
        assertEquals(RagErrorCode.EVIDENCE_NOT_EFFECTIVE,
                result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("markCited：仅回答中出现的 [S1] 被标记")
    void markCitedByMarkers() {
        List<EvidenceItem> evidence = evidence(3);
        service.markCited("结论内容 [S1]，补充 [S1]", evidence);

        assertTrue(evidence.get(0).isCited());
        assertFalse(evidence.get(1).isCited());
        assertFalse(evidence.get(2).isCited());
    }

    @Test
    @DisplayName("resolveUrl：危险链接返回 null，无来源时为 null")
    void resolveUrlGuarded() {
        assertNull(service.resolveUrl("javascript:alert(1)"));
        assertNull(service.resolveUrl(null));
        assertEquals("https://example.com/x",
                service.resolveUrl("https://example.com/x"));
    }

    // ---- fixtures ----

    private List<EvidenceItem> evidence(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> {
                    EvidenceItem item = new EvidenceItem();
                    item.setSourceRef("S" + i);
                    item.setDocumentId("doc-" + i);
                    item.setChunkId("chunk-" + i);
                    item.setContent("内容 " + i);
                    item.setTitle("手册 " + i);
                    return item;
                }).toList();
    }

    private List<KnowledgeDocumentPO> documentPOs(int count, String lifecycle) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> {
                    KnowledgeDocumentPO po = new KnowledgeDocumentPO();
                    po.setDocumentId("doc-" + i);
                    po.setLifecycleStatus(lifecycle);
                    return po;
                }).toList();
    }

    private List<DocumentChunkIndexPO> chunkPOs(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> chunk("chunk-" + i, "doc-" + i))
                .toList();
    }

    private DocumentChunkIndexPO chunk(String chunkId, String documentId) {
        DocumentChunkIndexPO po = new DocumentChunkIndexPO();
        po.setChunkId(chunkId);
        po.setDocumentId(documentId);
        return po;
    }
}
