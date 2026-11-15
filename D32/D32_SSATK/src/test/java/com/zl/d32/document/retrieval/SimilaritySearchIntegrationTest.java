package com.zl.d32.document.retrieval;

import com.zl.d32.document.chunk.model.Chunk;
import com.zl.d32.document.chunk.model.ChunkStrategy;
import com.zl.d32.document.retrieval.dto.SimilarityMatchView;
import com.zl.d32.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d32.document.retrieval.dto.SimilaritySearchResultView;
import com.zl.d32.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d32.document.vectorstore.support.EnabledIfPgVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D32 相似度检索真实集成测试：连接真实 PostgreSQL/pgvector 与真实 MySQL。
 *
 * <p>通过真实 D31 入库路径（真实 Embedding、真实 PG、真实关联）写入受控种子语料，
 * 验证 Top-5 排序与真实分数、元数据/版本过滤真实下推，并实证框架内置过滤器
 * 采用 PostgreSQL SQL/JSON Path（{@code metadata::jsonb @@ jsonpath}）而非把
 * {@code ==} 当作裸 SQL 运算符。测试结束按真实删除路径清理本测试作用域数据。</p>
 */
@EnabledIfPgVector
@SpringBootTest
class SimilaritySearchIntegrationTest {

    @Autowired
    private SimilaritySearchService similaritySearchService;

    @Autowired
    private DocumentVectorIndexService indexService;

    @Autowired
    private PgVectorStore pgVectorStore;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgJdbc;

    private String docAir;

    private String docPump;

    private final List<String> scopedDocuments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        docAir = "d32it-air-" + suffix;
        docPump = "d32it-pump-" + suffix;
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
    void unfilteredTop5ReturnsOrderedRealScores() {
        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery("空压机运行温度过高怎么处理");
        SimilaritySearchResultView result = similaritySearchService.search(request);

        assertEquals(5, result.getTopK());
        assertFalse(result.isFiltered());
        assertTrue(result.getTotal() > 0);
        assertTrue(result.getTotal() <= 5);

        double previousDistance = -1.0d;
        for (SimilarityMatchView match : result.getMatches()) {
            // 真实分数边界与语义。
            assertTrue(match.getDistance() >= 0.0d && match.getDistance() <= 1.0d);
            assertTrue(match.getSimilarity() >= 0.0d && match.getSimilarity() <= 1.0d);
            assertEquals(1.0d - match.getDistance(), match.getSimilarity(), 1e-6);
            // 距离升序（非递减）。
            assertTrue(match.getDistance() >= previousDistance - 1e-9);
            previousDistance = match.getDistance();
            assertTrue(match.getChunkId() != null && !match.getChunkId().isBlank());
            assertTrue(match.getContent() != null && !match.getContent().isBlank());
        }
    }

    @Test
    void documentIdFilterIsPushedDown() {
        // 查询用字（检查/运行/压力/磨损）在两个文档的 Chunk 正文里都真实出现，
        // 未过滤应能同时覆盖两个文档域。
        String sharedQuery = "检查运行压力磨损";

        SimilaritySearchRequest unfiltered = new SimilaritySearchRequest();
        unfiltered.setQuery(sharedQuery);
        unfiltered.setTopK(10);
        SimilaritySearchResultView all = similaritySearchService.search(unfiltered);
        long distinctDocs = all.getMatches().stream()
                .map(SimilarityMatchView::getDocumentId)
                .distinct().count();
        assertTrue(distinctDocs >= 2, "未过滤结果应覆盖至少两个文档，实际=" + distinctDocs);

        // 过滤 docAir：所有命中必须来自该文档。
        SimilaritySearchRequest filtered = new SimilaritySearchRequest();
        filtered.setQuery(sharedQuery);
        filtered.setTopK(10);
        filtered.setDocumentId(docAir);
        SimilaritySearchResultView onlyAir = similaritySearchService.search(filtered);

        assertTrue(onlyAir.isFiltered());
        assertTrue(onlyAir.getTotal() > 0);
        assertTrue(onlyAir.getMatches().stream()
                .allMatch(m -> docAir.equals(m.getDocumentId())));
        assertTrue(onlyAir.getMatches().stream()
                .noneMatch(m -> docPump.equals(m.getDocumentId())));
    }

    @Test
    void versionFilterIsPushedDown() {
        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery("检查运行压力磨损");
        request.setTopK(10);
        request.setVersion(1);
        SimilaritySearchResultView result = similaritySearchService.search(request);

        assertTrue(result.getTotal() > 0);
        assertTrue(result.getMatches().stream().allMatch(m -> Integer.valueOf(1).equals(m.getVersion())));
    }

    @Test
    void strategyFilterIsPushedDown() {
        SimilaritySearchRequest request = new SimilaritySearchRequest();
        request.setQuery("检查运行压力磨损");
        request.setTopK(10);
        request.setStrategy(ChunkStrategy.STRUCTURE.name());
        SimilaritySearchResultView result = similaritySearchService.search(request);

        assertTrue(result.getTotal() > 0);
        assertTrue(result.getMatches().stream()
                .allMatch(m -> ChunkStrategy.STRUCTURE.name().equals(m.getStrategy())));
    }

    @Test
    void frameworkNativeFilterExpressionIsValidAndPushedDown() {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression expression = builder.eq("documentId", docAir).build();
        SearchRequest request = SearchRequest.builder()
                .query("检查运行压力磨损")
                .topK(10)
                .filterExpression(expression)
                .build();

        // 实证：内置过滤器渲染为 SQL/JSON Path（jsonpath 内的 == 是合法相等运算符），
        // 调用不报错且结果仅包含目标文档。
        List<Document> documents = pgVectorStore.similaritySearch(request);
        assertFalse(documents.isEmpty());
        assertTrue(documents.stream().allMatch(d ->
                docAir.equals(d.getMetadata().get("documentId"))));
        assertTrue(documents.stream().noneMatch(d ->
                docPump.equals(d.getMetadata().get("documentId"))));
    }

    private void seed() {
        List<Chunk> airChunks = List.of(
                chunk(docAir, "空压机维修手册", 1, 0, "空压机运行温度过高，应先检查冷却器是否堵塞以及润滑油液位与油质，必要时清洗冷却器并更换润滑油。"),
                chunk(docAir, "空压机维修手册", 1, 1, "空压机排气压力不足时，检查进气阀开度、管路是否泄漏以及压力开关设定值。"),
                chunk(docAir, "空压机维修手册", 1, 2, "空压机启动后立即停机，需排查过载保护、电机接线与急停回路状态。"),
                chunk(docAir, "空压机维修手册", 1, 3, "空压机异响多来自轴承磨损、联轴器松动或润滑油不足，应停机检查并紧固。"),
                chunk(docAir, "空压机维修手册", 1, 4, "空压机排水阀堵塞会导致压缩空气含水升高，应定期清洗疏水阀与储气罐排污。"),
                chunk(docAir, "空压机维修手册", 1, 5, "空压机日常维护应记录运行温度、压力与油位，并按周期更换空滤、油滤和润滑油。"));
        indexService.indexChunks(docAir, 1, ChunkStrategy.STRUCTURE.name(), "空压机维修手册", airChunks);

        List<Chunk> pumpChunks = List.of(
                chunk(docPump, "水泵维修手册", 1, 0, "水泵运行异响且振动大，常见原因为叶轮卡滞、轴承损坏或泵内进入异物，应停机拆解检查。"),
                chunk(docPump, "水泵维修手册", 1, 1, "水泵出水压力不足，检查叶轮磨损、密封件泄漏以及进水口是否漏气或堵塞。"),
                chunk(docPump, "水泵维修手册", 1, 2, "水泵电机不启动，先确认电源与热继电器，再排查电机绕组与启动电容。"),
                chunk(docPump, "水泵维修手册", 1, 3, "水泵机械密封漏水需更换密封件，并核对轴套磨损与安装对中。"));
        indexService.indexChunks(docPump, 1, ChunkStrategy.STRUCTURE.name(), "水泵维修手册", pumpChunks);

        Long rows = pgJdbc.queryForObject(
                "SELECT COUNT(*) FROM public.d32_vector_store WHERE metadata->>'documentId' IN (?, ?)",
                Long.class, docAir, docPump);
        assertEquals(10L, rows, "种子语料应写入 10 个向量");
    }

    private Chunk chunk(String documentId, String title, int version, int index, String content) {
        Chunk chunk = new Chunk();
        chunk.setDocumentId(documentId);
        chunk.setTitle(title);
        chunk.setVersion(version);
        chunk.setStrategy(ChunkStrategy.STRUCTURE);
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setLength(content.length());
        return chunk;
    }
}
