package com.zl.d31.document.vectorstore;

import com.zl.d31.document.vectorstore.dto.VectorMatchView;
import com.zl.d31.document.vectorstore.dto.VectorSearchRequest;
import com.zl.d31.document.vectorstore.dto.VectorWriteRequest;
import com.zl.d31.document.vectorstore.support.EnabledIfPgVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pgvector 真实集成测试：连接真实 PostgreSQL，验证上下文装配、向量列类型、
 * 真实写入、余弦相似度检索、维度不匹配拒绝与 HNSW 索引。
 *
 * <p>仅在数据库可达时执行（{@link EnabledIfPgVector}）；测试数据带唯一标记，
 * 清理严格限定在本测试标记范围内，不触碰用户数据。</p>
 */
@EnabledIfPgVector
@SpringBootTest
class PgVectorStoreIntegrationTest {

    private static final String MARKER = "d31-it-marker";

    @Autowired
    private PgVectorStoreService service;

    @Autowired
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgVectorJdbcTemplate;

    @Autowired
    private PgVectorStoreProperties properties;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @BeforeEach
    @AfterEach
    void cleanScopedRows() {
        pgVectorJdbcTemplate.update(
                "DELETE FROM " + service.qualifiedTable() + " WHERE metadata->>'testRun' = ?", MARKER);
    }

    @Test
    void contextHasExactlyOneVectorStoreBean() {
        assertInstanceOf(PgVectorStore.class, vectorStore);
        // 自动装配因 @ConditionalOnMissingBean 回退，容器内仅一个 VectorStore
        String[] names = applicationContext.getBeanNamesForType(
                org.springframework.ai.vectorstore.VectorStore.class);
        assertEquals(1, names.length);
        assertEquals("pgVectorStore", names[0]);
    }

    @Test
    void embeddingColumnIsVector384() {
        String type = pgVectorJdbcTemplate.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ? AND a.attname = 'embedding'
                """, String.class, properties.getSchema(), properties.getTable());
        assertEquals("vector(384)", type);
    }

    @Test
    void realWriteIncreasesCount() {
        long before = service.count();
        int written = service.addItems(List.of(
                item("postgres pgvector 向量数据库 存储", MARKER),
                item("java spring 后端 服务 开发", MARKER),
                item("redis 缓存 会话 设计", MARKER)));
        long after = service.count();
        assertEquals(3, written);
        assertEquals(before + 3, after);
    }

    @Test
    void similaritySearchRanksLexicallyRelatedFirst() {
        service.addItems(List.of(
                item("postgres pgvector 向量数据库 存储", MARKER),
                item("java spring 后端 服务 开发", MARKER),
                item("redis 缓存 会话 设计", MARKER)));

        VectorSearchRequest request = new VectorSearchRequest();
        request.setQuery("pgvector 向量 数据库");
        request.setTopK(3);
        List<VectorMatchView> matches = service.search(request);

        assertEquals(3, matches.size());
        assertEquals("postgres pgvector 向量数据库 存储", matches.get(0).getText());
        // 得分降序，且余弦相似度落在 [0,1]
        for (int i = 0; i < matches.size(); i++) {
            double score = matches.get(i).getScore();
            assertTrue(score >= 0.0 && score <= 1.0, "score 越界：" + score);
            if (i > 0) {
                assertTrue(matches.get(i - 1).getScore() >= matches.get(i).getScore(),
                        "结果未按相似度降序");
            }
        }
    }

    @Test
    void wrongDimensionInsertIsRejected() {
        DataAccessException ex = assertThrows(DataAccessException.class, () ->
                pgVectorJdbcTemplate.update(
                        "INSERT INTO " + service.qualifiedTable()
                                + " (content, embedding) VALUES (?, '[1,2,3]'::vector)",
                        "wrong-dimension"));
        assertNotNull(ex.getMessage());
    }

    @Test
    void hnswIndexExistsOnVectorTable() {
        Integer count = pgVectorJdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = ? AND tablename = ? AND indexdef ILIKE '%USING hnsw%'
                """, Integer.class, properties.getSchema(), properties.getTable());
        assertEquals(1, count);
    }

    private VectorWriteRequest.Item item(String text, String marker) {
        VectorWriteRequest.Item item = new VectorWriteRequest.Item();
        item.setText(text);
        item.setMetadata(Map.of("testRun", marker));
        return item;
    }
}
