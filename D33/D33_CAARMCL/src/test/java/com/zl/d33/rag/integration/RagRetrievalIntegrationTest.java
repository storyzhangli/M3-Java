package com.zl.d33.rag.integration;

import com.zl.d33.document.chunk.model.Chunk;
import com.zl.d33.document.chunk.model.ChunkStrategy;
import com.zl.d33.document.retrieval.dto.SimilarityMatchView;
import com.zl.d33.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d33.rag.context.ContextAssemblyResult;
import com.zl.d33.rag.context.ContextAssemblyService;
import com.zl.d33.rag.model.RetrievalStatus;
import com.zl.d33.rag.retrieval.RagRetrievalQuery;
import com.zl.d33.rag.retrieval.RagRetrievalResult;
import com.zl.d33.rag.retrieval.RagRetrievalService;
import com.zl.d33.rag.support.EnabledIfPgVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D33 RAG 真实集成测试：真实 PostgreSQL/pgvector + 真实 384 维 Embedding，
 * 验证“检索服务 → 上下文组装”链路在真实基础设施上的行为。
 *
 * <p>通过真实入库路径写入受控种子，仅清理本测试作用域数据（唯一 docId），
 * 不触碰用户既有数据；PG 不可达时由门控禁用，不冒充通过。</p>
 */
@EnabledIfPgVector
@SpringBootTest
class RagRetrievalIntegrationTest {

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @Autowired
    private ContextAssemblyService contextAssemblyService;

    @Autowired
    private DocumentVectorIndexService indexService;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgJdbc;

    private String docAir;

    private String docPump;

    private final List<String> scopedDocuments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        docAir = "d33it-air-" + suffix;
        docPump = "d33it-pump-" + suffix;
        scopedDocuments.add(docAir);
        scopedDocuments.add(docPump);
        seed();
    }

    @AfterEach
    void tearDown() {
        for (String documentId : scopedDocuments) {
            indexService.deleteByDocument(documentId);
        }
    }

    @Test
    @DisplayName("真实检索成功且按距离升序返回真实分数")
    void realRetrievalShouldSucceed() {
        RagRetrievalResult result = ragRetrievalService.retrieve(
                new RagRetrievalQuery("空压机运行温度过高怎么处理", 5, null,
                        null, null, null));

        assertEquals(RetrievalStatus.SUCCESS, result.status());
        assertTrue(result.retrievedCount() > 0 && result.retrievedCount() <= 5);

        double previous = -1.0d;
        for (SimilarityMatchView match : result.matches()) {
            assertTrue(match.getDistance() >= 0.0d && match.getDistance() <= 1.0d);
            assertTrue(match.getDistance() >= previous - 1e-9);
            previous = match.getDistance();
        }
    }

    @Test
    @DisplayName("documentId 过滤真实下推，命中仅来自目标文档")
    void filterShouldBePushedDown() {
        RagRetrievalResult result = ragRetrievalService.retrieve(
                new RagRetrievalQuery("检查运行压力磨损", 10, docAir,
                        null, null, null));

        assertEquals(RetrievalStatus.SUCCESS, result.status());
        assertTrue(result.matches().stream().allMatch(m -> docAir.equals(m.getDocumentId())));
        assertTrue(result.matches().stream().noneMatch(m -> docPump.equals(m.getDocumentId())));
    }

    @Test
    @DisplayName("真实检索 → 组装产生证据与 [S1] 上下文，来源映射真实元数据")
    void retrievalThenAssemblyShouldProduceEvidence() {
        RagRetrievalResult retrieval = ragRetrievalService.retrieve(
                new RagRetrievalQuery("空压机运行温度过高怎么处理", 5, null,
                        null, null, null));
        ContextAssemblyResult context = contextAssemblyService.assemble(retrieval.matches());

        assertTrue(context.hasEvidence());
        assertEquals("S1", context.getEvidence().get(0).getSourceRef());
        assertTrue(context.getContextText().contains("[S1]"));
        assertTrue(context.getContextText().length() <= 6000);
        assertEquals("CHARACTER", context.getBudget().getBudgetUnit());
    }

    @Test
    @DisplayName("过滤不存在的文档 → 真实 EMPTY 分支（成功但无命中）")
    void nonexistentFilterShouldReturnEmpty() {
        RagRetrievalResult result = ragRetrievalService.retrieve(
                new RagRetrievalQuery("任意问题", 5, "d33it-not-exist-zzz",
                        null, null, null));

        assertEquals(RetrievalStatus.EMPTY, result.status());
        assertEquals(0, result.retrievedCount());
    }

    private void seed() {
        List<Chunk> airChunks = List.of(
                chunk(docAir, "空压机维修手册", 0, "空压机运行温度过高，应先检查冷却器是否堵塞以及润滑油液位与油质，必要时清洗冷却器并更换润滑油。"),
                chunk(docAir, "空压机维修手册", 1, "空压机排气压力不足时，检查进气阀开度、管路是否泄漏以及压力开关设定值。"),
                chunk(docAir, "空压机维修手册", 2, "空压机启动后立即停机，需排查过载保护、电机接线与急停回路状态。"),
                chunk(docAir, "空压机维修手册", 3, "空压机异响多来自轴承磨损、联轴器松动或润滑油不足，应停机检查并紧固。"),
                chunk(docAir, "空压机维修手册", 4, "空压机排水阀堵塞会导致压缩空气含水升高，应定期清洗疏水阀与储气罐排污。"),
                chunk(docAir, "空压机维修手册", 5, "空压机日常维护应记录运行温度、压力与油位，并按周期更换空滤、油滤和润滑油。"));
        indexService.indexChunks(docAir, 1, ChunkStrategy.STRUCTURE.name(), "空压机维修手册", airChunks);

        List<Chunk> pumpChunks = List.of(
                chunk(docPump, "水泵维修手册", 0, "水泵运行异响且振动大，常见原因为叶轮卡滞、轴承损坏或泵内进入异物，应停机拆解检查。"),
                chunk(docPump, "水泵维修手册", 1, "水泵出水压力不足，检查叶轮磨损、密封件泄漏以及进水口是否漏气或堵塞。"),
                chunk(docPump, "水泵维修手册", 2, "水泵电机不启动，先确认电源与热继电器，再排查电机绕组与启动电容。"),
                chunk(docPump, "水泵维修手册", 3, "水泵机械密封漏水需更换密封件，并核对轴套磨损与安装对中。"));
        indexService.indexChunks(docPump, 1, ChunkStrategy.STRUCTURE.name(), "水泵维修手册", pumpChunks);

        Long rows = pgJdbc.queryForObject(
                "SELECT COUNT(*) FROM public.d33_vector_store WHERE metadata->>'documentId' IN (?, ?)",
                Long.class, docAir, docPump);
        assertEquals(10L, rows, "种子语料应写入 10 个向量");
    }

    private Chunk chunk(String documentId, String title, int index, String content) {
        Chunk chunk = new Chunk();
        chunk.setDocumentId(documentId);
        chunk.setTitle(title);
        chunk.setVersion(1);
        chunk.setStrategy(ChunkStrategy.STRUCTURE);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setLength(content.length());
        return chunk;
    }
}
