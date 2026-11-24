package com.zl.d41.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d41.document.mapper.DocumentChunkIndexMapper;
import com.zl.d41.document.mapper.KnowledgeDocumentMapper;
import com.zl.d41.document.po.DocumentChunkIndexPO;
import com.zl.d41.document.po.KnowledgeDocumentPO;
import com.zl.d41.rag.citation.CitationVerificationService;
import com.zl.d41.rag.context.EvidenceItem;
import com.zl.d41.rag.model.RagErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C17/D8：tombstone 删除语义下引用核验分类路径单测（真实 classify 逻辑，仅 mapper I/O mock）。
 *
 * <p>关键断言：删除闭环（lifecycle→DEPRECATED ∧ chunk 关联行清除）后，
 * CitationVerificationService 走 <b>chunkOwners-missing 分支</b>（非 doc-null 分支）
 * 给出 CITATION_TARGET_DELETED——D35 引用链路不回退（§3.6 冻结口径）。
 * Bean 级端到端（真实库删除后调用）经 P5 矩阵 HTTP+DB 事实闭环佐证；
 * 生成中竞态 mock 不可观测 → N/A 留痕（H8），不在此伪造。</p>
 */
class CitationDeletedClassificationTest {

    private KnowledgeDocumentMapper documentMapper;
    private DocumentChunkIndexMapper chunkMapper;
    private CitationVerificationService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        chunkMapper = mock(DocumentChunkIndexMapper.class);
        service = new CitationVerificationService(documentMapper, chunkMapper);
    }

    private static EvidenceItem item(String docId, String chunkId) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef("S1");
        item.setDocumentId(docId);
        item.setChunkId(chunkId);
        item.setContent("c");
        return item;
    }

    private static KnowledgeDocumentPO doc(String docId, String lifecycle) {
        KnowledgeDocumentPO po = new KnowledgeDocumentPO();
        po.setDocumentId(docId);
        po.setLifecycleStatus(lifecycle);
        return po;
    }

    private static DocumentChunkIndexPO chunk(String chunkId, String docId) {
        DocumentChunkIndexPO po = new DocumentChunkIndexPO();
        po.setChunkId(chunkId);
        po.setDocumentId(docId);
        return po;
    }

    @Test
    @DisplayName("tombstone（DEPRECATED 行保留）+ chunk 行已清 → CITATION_TARGET_DELETED（chunkOwners 分支）")
    void tombstoneDeletedChunkYieldsTargetDeleted() {
        when(documentMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(doc("d41-doc-c", "DEPRECATED")));
        when(chunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        CitationVerificationService.CitationVerification result =
                service.verify(List.of(item("d41-doc-c", "chunk-x")));

        assertEquals(1, result.removed().size());
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED, result.removed().get(0).reasonCode());
        // 非 doc-null 分支：文档行仍在（证明走了 chunkOwners-missing 判定而非存在性判定）
        assertEquals("chunk-x", result.removed().get(0).chunkId());
    }

    @Test
    @DisplayName("doc 行不存在（硬删域）→ 同码 CITATION_TARGET_DELETED，两分支收敛一致")
    void docNullAlsoTargetDeleted() {
        when(documentMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(chunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        CitationVerificationService.CitationVerification result =
                service.verify(List.of(item("d41-doc-gone", "chunk-x")));
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED, result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("分支区分度：DEPRECATED 但 chunk 行残留（中间态）→ EVIDENCE_NOT_EFFECTIVE 而非 DELETED")
    void deprecatedWithChunkStillYieldsNotEffective() {
        when(documentMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(doc("d41-doc-c", "DEPRECATED")));
        when(chunkMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(chunk("chunk-x", "d41-doc-c")));

        CitationVerificationService.CitationVerification result =
                service.verify(List.of(item("d41-doc-c", "chunk-x")));
        assertEquals(RagErrorCode.EVIDENCE_NOT_EFFECTIVE, result.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("健康态：ACTIVE + 归属一致 → valid（对照组，防过度剔除）")
    void healthyEvidencePasses() {
        when(documentMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(doc("d41-doc-a", "ACTIVE")));
        when(chunkMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(chunk("chunk-a1", "d41-doc-a")));

        CitationVerificationService.CitationVerification result =
                service.verify(List.of(item("d41-doc-a", "chunk-a1")));
        assertEquals(1, result.validItems().size());
        assertEquals(0, result.removed().size());
    }

    @Test
    @DisplayName("归属漂移：chunk 属他文档 → CITATION_OWNERSHIP_MISMATCH（与删除码不混用）")
    void ownershipMismatchDistinct() {
        when(documentMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(doc("d41-doc-a", "ACTIVE")));
        when(chunkMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(chunk("chunk-a1", "d41-doc-b")));

        CitationVerificationService.CitationVerification result =
                service.verify(List.of(item("d41-doc-a", "chunk-a1")));
        assertEquals(RagErrorCode.CITATION_OWNERSHIP_MISMATCH, result.removed().get(0).reasonCode());
    }
}
