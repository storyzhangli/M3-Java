package com.zl.d34.rag.real;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d34.document.chunk.model.Chunk;
import com.zl.d34.document.chunk.model.ChunkStrategy;
import com.zl.d34.document.mapper.KnowledgeDocumentMapper;
import com.zl.d34.document.model.DocumentLifecycleStatus;
import com.zl.d34.document.po.KnowledgeDocumentPO;
import com.zl.d34.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d34.rag.dto.RagQuestionRequest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

/**
 * D34 真实端到端测试共享支撑：固定受控文档、Chunk 构建、幂等夹具与清理。
 *
 * <p>受控文档（固定 ID，测试前后均清理，避免污染真实数据）：</p>
 * <ul>
 *   <li>{@code l5docA}：单主体单 Chunk，含明确参数，用于接地 ANSWER；</li>
 *   <li>{@code l5docB}：同主体两 Chunk 同参数不同值，用于冲突 CONFLICT；</li>
 *   <li>{@code l5docC}：与问题无关的包装说明，用于低相关 LOW_RELEVANCE。</li>
 * </ul>
 */
abstract class RagRealTestSupport {

    static final String DOC_A = "l5docA";
    static final String DOC_B = "l5docB";
    static final String DOC_C = "l5docC";
    static final String DOC_MISSING = "l5docMissing";

    static final String TITLE_A = "L5设备甲";
    static final String TITLE_B = "L5设备乙";
    static final String TITLE_C = "L5设备丙";

    @Autowired
    protected DocumentVectorIndexService indexService;

    @Autowired
    protected KnowledgeDocumentMapper documentMapper;

    /** 重建全部受控夹具：先清理旧数据，再写入 MySQL 生命周期行并入库向量（Embedding 本地，不产生模型调用）。 */
    protected void ensureFixtures() {
        cleanupFixtures();
        ensureLifecycleRows();
        indexService.indexChunks(DOC_A, 1, ChunkStrategy.STRUCTURE.name(), TITLE_A, List.of(
                chunk(DOC_A, TITLE_A, 0,
                        "L5设备甲技术手册。额定压力: 0.8 MPa；工作温度: 60 ℃；设备运行状态正常，运行稳定。")));

        indexService.indexChunks(DOC_B, 1, ChunkStrategy.STRUCTURE.name(), TITLE_B, List.of(
                chunk(DOC_B, TITLE_B, 0,
                        "L5设备乙运行记录第一版。额定压力: 1.2 MPa；记录状态正常。"),
                chunk(DOC_B, TITLE_B, 1,
                        "L5设备乙运行记录第二版。额定压力: 1.6 MPa；记录状态正常。")));

        indexService.indexChunks(DOC_C, 1, ChunkStrategy.STRUCTURE.name(), TITLE_C, List.of(
                chunk(DOC_C, TITLE_C, 0,
                        "L5设备丙包装与外观说明。设备外壳为蓝色，包装清单含说明书与附件，不含技术参数。")));
    }

    protected void cleanupFixtures() {
        for (String doc : List.of(DOC_A, DOC_B, DOC_C)) {
            indexService.deleteByDocument(doc);
        }
        cleanupLifecycleRows();
    }

    /** 为受控文档写入 ACTIVE 生命周期行，使 fail-closed 下正常接地路径可被解析为 ACTIVE。 */
    private void ensureLifecycleRows() {
        LocalDateTime now = LocalDateTime.now();
        for (String doc : List.of(DOC_A, DOC_B, DOC_C)) {
            KnowledgeDocumentPO po = new KnowledgeDocumentPO();
            po.setDocumentId(doc);
            po.setSource("D34-REMEDIATION-IT");
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

    private void cleanupLifecycleRows() {
        documentMapper.delete(new QueryWrapper<KnowledgeDocumentPO>()
                .in("document_id", List.of(DOC_A, DOC_B, DOC_C)));
    }

    protected RagQuestionRequest request(String question, String documentId, int topK) {
        RagQuestionRequest req = new RagQuestionRequest();
        req.setQuestion(question);
        req.setDocumentId(documentId);
        req.setTopK(topK);
        return req;
    }

    protected Chunk chunk(String documentId, String title, int index, String content) {
        return new Chunk(documentId, title, 1, ChunkStrategy.STRUCTURE,
                index, content, 0, content.length(), List.of());
    }
}
