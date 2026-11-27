package com.zl.d44.sync;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d44.document.mapper.DocumentChunkIndexMapper;
import com.zl.d44.document.mapper.KnowledgeDocumentMapper;
import com.zl.d44.document.po.DocumentChunkIndexPO;
import com.zl.d44.document.po.KnowledgeDocumentPO;
import com.zl.d44.rag.citation.CitationVerificationService;
import com.zl.d44.rag.context.EvidenceItem;
import com.zl.d44.rag.model.RagErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D44 C06 引用真实·服务级结构断言（H4 三层之①，答辩迭代新增，不改既有测试）。
 *
 * <p>样本 chunkId/documentId 取 KP2 实测冻结语料 8 行 CANON 值（docs/evidence/D44/probe/
 * kp2-dual-anchor.txt），使受控桩数据与真实入库行逐字一致；数据级存在性对账由
 * d44_verify_citation.ps1 走 JDBC 完成，在线级 citations=0 不误放行同脚本覆盖。</p>
 *
 * <p>边界声明：mock 态 sources 恒空（D43 C12），本类为结构断言，不构成模型级引用真实验证
 * （NOT RUN，见 defense-protocol.json C1_citation_truth.boundary）。</p>
 */
class CitationTruthDefenseTest {

    /** KP2 冻结语料真实行（DOC-CNC-COOLING-001 第 2 片段）。 */
    private static final String REAL_CHUNK_ID = "2acc1c17-6750-595a-b604-086a7b4b2e78";
    private static final String REAL_DOC_ID = "DOC-CNC-COOLING-001";
    /** 跨归属陷阱：真实 chunk + 另一真实文档 ID（同库存在但归属不同）。 */
    private static final String OTHER_DOC_ID = "DOC-SERVO-TROUBLE-002";

    private KnowledgeDocumentMapper documentMapper;
    private DocumentChunkIndexMapper chunkMapper;
    private CitationVerificationService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        chunkMapper = mock(DocumentChunkIndexMapper.class);
        service = new CitationVerificationService(documentMapper, chunkMapper);
    }

    private static EvidenceItem item(String ref, String docId, String chunkId) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(ref);
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

    /** 桩：两真实文档 ACTIVE + 真实 chunk 归属 CNC（与 KP2 冻结行一致）。 */
    private void stubFrozenCorpusWorld() {
        when(documentMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(doc(REAL_DOC_ID, "ACTIVE"), doc(OTHER_DOC_ID, "ACTIVE")));
        when(chunkMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(chunk(REAL_CHUNK_ID, REAL_DOC_ID)));
    }

    @Test
    @DisplayName("层1-a 真实 chunkId（KP2 冻结 CANON 行）→ 核验通过，不误剔除")
    void realChunkIdFromFrozenCorpusPasses() {
        stubFrozenCorpusWorld();
        var r = service.verify(List.of(item("S1", REAL_DOC_ID, REAL_CHUNK_ID)));
        assertEquals(1, r.validItems().size());
        assertEquals(0, r.removed().size());
        assertEquals(RagErrorCode.NONE, r.reasonByRef().get("S1"));
    }

    @Test
    @DisplayName("层1-b 伪造 chunkId（索引无此行）→ 剔除，分类 CITATION_TARGET_DELETED")
    void fabricatedChunkIdIsRemoved() {
        stubFrozenCorpusWorld();
        var r = service.verify(List.of(item("S1", REAL_DOC_ID, "deadbeef-0000-0000-0000-000000000000")));
        assertEquals(0, r.validItems().size());
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED, r.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("层1-c 跨归属伪造（真实 chunk × 他人 docId）→ CITATION_OWNERSHIP_MISMATCH，防拼接返回")
    void crossOwnershipFabricationIsRemoved() {
        stubFrozenCorpusWorld();
        var r = service.verify(List.of(item("S1", OTHER_DOC_ID, REAL_CHUNK_ID)));
        assertEquals(0, r.validItems().size());
        assertEquals(RagErrorCode.CITATION_OWNERSHIP_MISMATCH, r.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("层1-d 已删目标（doc DEPRECATED 且 chunk 行已清）→ CITATION_TARGET_DELETED")
    void deletedTargetIsRemoved() {
        when(documentMapper.selectList(any(QueryWrapper.class)))
                .thenReturn(List.of(doc(REAL_DOC_ID, "DEPRECATED")));
        when(chunkMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        var r = service.verify(List.of(item("S1", REAL_DOC_ID, REAL_CHUNK_ID)));
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED, r.removed().get(0).reasonCode());
    }

    @Test
    @DisplayName("层1-e 混合批（真+伪+已删）→ 仅真实项存活，剔除计数=2，ref 级归因齐全")
    void mixedBatchKeepsOnlyValid() {
        stubFrozenCorpusWorld();
        var r = service.verify(List.of(
                item("S1", REAL_DOC_ID, REAL_CHUNK_ID),
                item("S2", REAL_DOC_ID, "ffffffff-0000-0000-0000-000000000000"),
                item("S3", "DOC-NEVER-REGISTERED", "00000000-0000-0000-0000-000000000000")));
        assertEquals(1, r.validItems().size());
        assertEquals(REAL_CHUNK_ID, r.validItems().get(0).getChunkId());
        assertEquals(2, r.removed().size());
        assertTrue(r.hasRemoval());
        assertEquals(RagErrorCode.NONE, r.reasonByRef().get("S1"));
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED, r.reasonByRef().get("S2"));
        assertEquals(RagErrorCode.CITATION_TARGET_DELETED, r.reasonByRef().get("S3"));
    }
}
