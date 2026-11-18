package com.zl.d35.rag.real;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d35.document.chunk.model.Chunk;
import com.zl.d35.document.chunk.model.ChunkSource;
import com.zl.d35.document.chunk.model.ChunkStrategy;
import com.zl.d35.document.mapper.KnowledgeDocumentMapper;
import com.zl.d35.document.model.DocumentLifecycleStatus;
import com.zl.d35.document.po.KnowledgeDocumentPO;
import com.zl.d35.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d35.rag.dto.RagQuestionRequest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

/**
 * D35 真实端到端测试共享支撑：固定受控文档、带页码/段落定位的 Chunk 构建、幂等夹具与清理。
 *
 * <p>所有受控文档使用固定 ID，测试前后均清理，仅隔离 fixture，不触碰真实文档与历史向量。
 * 入库经 Embedding 本地完成，不产生 ChatModel 调用。</p>
 */
abstract class RagRealTestSupport {

    static final String DOC_A = "d35l5docA";
    static final String DOC_B = "d35l5docB";
    static final String DOC_C = "d35l5docC";
    static final String DOC_DEL = "d35l5docDel";
    static final String DOC_MISSING = "d35l5docMissing";

    static final String TITLE_A = "D35设备甲";
    static final String TITLE_B = "D35设备乙";
    static final String TITLE_C = "D35设备丙";
    static final String TITLE_DEL = "D35待删设备";

    @Autowired
    protected DocumentVectorIndexService indexService;

    @Autowired
    protected KnowledgeDocumentMapper documentMapper;

    /** 重建全部受控夹具：先清理，再写 ACTIVE 生命周期行并入库向量。 */
    protected void ensureFixtures() {
        cleanupFixtures();
        ensureLifecycleRows();

        indexService.indexChunks(DOC_A, 1, ChunkStrategy.STRUCTURE.name(), TITLE_A, List.of(
                locatedChunk(DOC_A, TITLE_A, 0,
                        "D35设备甲技术手册。额定压力: 0.8 MPa；工作温度: 60 ℃；运行稳定。",
                        2, 1)));

        indexService.indexChunks(DOC_B, 1, ChunkStrategy.STRUCTURE.name(), TITLE_B, List.of(
                locatedChunk(DOC_B, TITLE_B, 0,
                        "D35设备乙手册第一段。额定压力: 0.8 MPa；记录状态正常。", 3, 0),
                locatedChunk(DOC_B, TITLE_B, 1,
                        "D35设备乙手册第二段。工作温度: 55 ℃；记录状态正常。", 3, 1),
                locatedChunk(DOC_B, TITLE_B, 2,
                        "D35设备乙手册第三段。额定功率: 200；记录状态正常。", 4, 2)));

        // 无可靠页码定位：sources 不带 pageNumber，验证 page 缺失隐藏不造
        indexService.indexChunks(DOC_C, 1, ChunkStrategy.STRUCTURE.name(), TITLE_C, List.of(
                locatedChunk(DOC_C, TITLE_C, 0,
                        "D35设备丙包装说明。设备外壳为蓝色，不含技术参数。", null, 0)));

        indexService.indexChunks(DOC_DEL, 1, ChunkStrategy.STRUCTURE.name(), TITLE_DEL, List.of(
                locatedChunk(DOC_DEL, TITLE_DEL, 0,
                        "D35待删设备手册。额定压力: 1.0 MPa；记录状态正常。", 5, 0)));
    }

    protected void cleanupFixtures() {
        for (String doc : List.of(DOC_A, DOC_B, DOC_C, DOC_DEL)) {
            indexService.deleteByDocument(doc);
        }
        documentMapper.delete(new QueryWrapper<KnowledgeDocumentPO>()
                .in("document_id", List.of(DOC_A, DOC_B, DOC_C, DOC_DEL)));
    }

    private void ensureLifecycleRows() {
        LocalDateTime now = LocalDateTime.now();
        for (String doc : List.of(DOC_A, DOC_B, DOC_C, DOC_DEL)) {
            KnowledgeDocumentPO po = new KnowledgeDocumentPO();
            po.setDocumentId(doc);
            po.setSource("D35-CITATION-IT");
            po.setTitle(doc);
            po.setVersion(1);
            po.setStatus("COMPLETED");
            po.setLifecycleStatus(DocumentLifecycleStatus.ACTIVE.name());
            po.setContentLength(0L);
            po.setErrorCode("");
            po.setErrorMessage("");
            po.setCreatedAt(now);
            po.setUpdatedAt(now);
            documentMapper.insert(po);
        }
    }

    protected RagQuestionRequest request(String question, String documentId, int topK) {
        RagQuestionRequest req = new RagQuestionRequest();
        req.setQuestion(question);
        req.setDocumentId(documentId);
        req.setTopK(topK);
        return req;
    }

    /** 带真实页码/段落定位的 Chunk；pageNumber 为 null 时表示无可靠页码。 */
    protected Chunk locatedChunk(String documentId, String title, int index,
                                 String content, Integer pageNumber, Integer paragraphIndex) {
        ChunkSource source = new ChunkSource(pageNumber, paragraphIndex, 0, content.length());
        return new Chunk(documentId, title, 1, ChunkStrategy.STRUCTURE,
                index, content, 0, content.length(), List.of(source));
    }
}
