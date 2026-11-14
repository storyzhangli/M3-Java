package com.zl.d31.document.vectorindex;

import com.zl.d31.document.chunk.model.Chunk;
import com.zl.d31.document.chunk.model.ChunkStrategy;
import com.zl.d31.document.po.DocumentChunkIndexPO;
import com.zl.d31.document.vectorindex.dto.ReconciliationReportView;
import com.zl.d31.document.vectorindex.dto.VectorIndexResultView;
import com.zl.d31.document.vectorindex.model.VectorIndexStatus;
import com.zl.d31.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d31.document.vectorindex.service.VectorReconciliationService;
import com.zl.d31.document.vectorstore.PgVectorStoreProperties;
import com.zl.d31.document.vectorstore.support.EnabledIfPgVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * D31 向量入库、更新与幂等真实集成测试。
 *
 * <p>连接真实 PostgreSQL（pgvector）与真实 MySQL，覆盖任务书要求的六种场景：
 * 新增、重复导入、更新（版本切换）、删除、失败重试、部分失败恢复，
 * 并补充 DB 唯一约束、并发幂等与核对补偿。失败仅通过 {@link SpyBean}
 * 包装真实 EmbeddingModel 注入异常，不 Mock 数据库，不伪造持久化。</p>
 */
@EnabledIfPgVector
@SpringBootTest
class DocumentVectorIndexIntegrationTest {

    private static final String PREFIX = "d31vi-%";

    @Autowired
    private DocumentVectorIndexService indexService;

    @Autowired
    private VectorReconciliationService reconciliationService;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgJdbc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PgVectorStoreProperties pgProperties;

    @SpyBean
    private EmbeddingModel embeddingModel;

    private String documentId;

    private int originalBatchSize;

    @BeforeEach
    void setUp() {
        documentId = "d31vi-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        originalBatchSize = indexService.getProperties().getBatchSize();
        cleanScoped();
    }

    @AfterEach
    void tearDown() {
        indexService.getProperties().setBatchSize(originalBatchSize);
        reset(embeddingModel);
        cleanScoped();
    }

    private void cleanScoped() {
        pgJdbc.update("DELETE FROM " + qualifiedTable()
                + " WHERE metadata->>'documentId' LIKE 'd31vi-%'");
        jdbcTemplate.update("DELETE FROM document_chunk_index WHERE document_id LIKE 'd31vi-%'");
        jdbcTemplate.update("DELETE FROM document_vector_index WHERE document_id LIKE 'd31vi-%'");
    }

    // 场景 1：新增入库 ----------------------------------------------------

    @Test
    void scenario1_firstIndex_completesAndPersists() {
        VectorIndexResultView view = indexService.indexChunks(documentId, 1,
                "STRUCTURE", "测试文档", sampleChunks(documentId, 1,
                        "向量数据库 pgvector 存储",
                        "java spring 后端 服务",
                        "redis 缓存 会话 设计"));

        assertEquals(VectorIndexStatus.COMPLETED.name(), view.getStatus());
        assertEquals(3, view.getChunkCount());
        assertFalse(view.isIdempotent());
        assertEquals(3, indexService.countPgForVersion(documentId, 1));
        List<DocumentChunkIndexPO> relations = indexService.listChunks(documentId);
        assertEquals(3, relations.size());
        Set<String> ids = relations.stream()
                .map(DocumentChunkIndexPO::getChunkId).collect(Collectors.toSet());
        assertEquals(3, ids.size());
        ids.forEach(id -> assertDoesNotThrow(() -> UUID.fromString(id)));
    }

    // 场景 2：重复导入幂等 ------------------------------------------------

    @Test
    void scenario2_repeatImport_isIdempotentWithoutDuplicates() {
        List<Chunk> chunks = sampleChunks(documentId, 1,
                "向量数据库 pgvector 存储", "java spring 后端 服务", "redis 缓存 会话 设计");
        indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档", chunks);
        Set<String> firstIds = indexService.listChunks(documentId).stream()
                .map(DocumentChunkIndexPO::getChunkId).collect(Collectors.toSet());

        VectorIndexResultView repeat = indexService.indexChunks(documentId, 1,
                "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1,
                        "向量数据库 pgvector 存储", "java spring 后端 服务", "redis 缓存 会话 设计"));

        assertTrue(repeat.isIdempotent());
        assertEquals(3, indexService.countPgForVersion(documentId, 1));
        Set<String> secondIds = indexService.listChunks(documentId).stream()
                .map(DocumentChunkIndexPO::getChunkId).collect(Collectors.toSet());
        assertEquals(firstIds, secondIds);
    }

    // 场景 3：更新与版本切换 ----------------------------------------------

    @Test
    void scenario3_update_switchesVersionAndCleansOld() {
        indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "旧版本 内容 甲", "旧版本 内容 乙", "旧版本 内容 丙"));

        indexService.indexChunks(documentId, 2, "STRUCTURE", "测试文档",
                sampleChunks(documentId, 2,
                        "新版本 内容 甲", "新版本 内容 乙", "新版本 内容 丙", "新版本 内容 丁"));

        assertEquals(0, indexService.countPgForVersion(documentId, 1));
        assertEquals(4, indexService.countPgForVersion(documentId, 2));
        var statuses = indexService.getStatus(documentId);
        assertEquals(VectorIndexStatus.SUPERSEDED.name(), statuses.get(0).getStatus());
        assertEquals(VectorIndexStatus.COMPLETED.name(), statuses.get(1).getStatus());
        assertEquals(4, indexService.listChunks(documentId).size());
    }

    @Test
    void scenario3b_newVersionFailure_keepsOldVersionIntact() {
        indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "旧版本 内容 甲", "旧版本 内容 乙", "旧版本 内容 丙"));

        doThrow(new RuntimeException("injected fault before new version write"))
                .when(embeddingModel).call(any(EmbeddingRequest.class));

        assertThrows(VectorIndexException.class, () ->
                indexService.indexChunks(documentId, 2, "STRUCTURE", "测试文档",
                        sampleChunks(documentId, 2, "新版本 内容 甲", "新版本 内容 乙")));

        assertEquals(3, indexService.countPgForVersion(documentId, 1));
        assertEquals(0, indexService.countPgForVersion(documentId, 2));
        var v1 = indexService.getStatus(documentId).stream()
                .filter(p -> p.getVersion() == 1).findFirst().orElseThrow();
        assertEquals(VectorIndexStatus.COMPLETED.name(), v1.getStatus());
    }

    // 场景 4：按文档删除 --------------------------------------------------

    @Test
    void scenario4_delete_removesAllAndIsRepeatSafe() {
        indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "内容 甲", "内容 乙", "内容 丙"));

        indexService.deleteByDocument(documentId);
        assertEquals(0, indexService.countPgForVersion(documentId, 1));
        assertEquals(0, indexService.listChunks(documentId).size());
        assertEquals(0, indexService.getStatus(documentId).size());

        assertDoesNotThrow(() -> indexService.deleteByDocument(documentId));
    }

    // 场景 5：失败重试 ----------------------------------------------------

    @Test
    void scenario5_failureThenRetry_completesWithoutNewIds() {
        List<Chunk> chunks = sampleChunks(documentId, 1,
                "内容 甲 向量", "内容 乙 向量", "内容 丙 向量");
        doThrow(new RuntimeException("injected first-attempt failure"))
                .when(embeddingModel).call(any(EmbeddingRequest.class));

        assertThrows(VectorIndexException.class, () ->
                indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档", chunks));
        assertEquals(0, indexService.countPgForVersion(documentId, 1));

        reset(embeddingModel);
        VectorIndexResultView recovered = indexService.indexChunks(documentId, 1,
                "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "内容 甲 向量", "内容 乙 向量", "内容 丙 向量"));

        assertEquals(VectorIndexStatus.COMPLETED.name(), recovered.getStatus());
        assertEquals(3, indexService.countPgForVersion(documentId, 1));
        assertEquals(3, indexService.listChunks(documentId).size());
        Set<String> ids = indexService.listChunks(documentId).stream()
                .map(DocumentChunkIndexPO::getChunkId).collect(Collectors.toSet());
        assertEquals(3, ids.size());
    }

    // 场景 6：部分失败恢复 ------------------------------------------------

    @Test
    void scenario6_partialFailureThenRecover_convergesCorrectly() {
        indexService.getProperties().setBatchSize(2);
        List<Chunk> chunks = sampleChunks(documentId, 1,
                "内容 甲", "内容 乙", "内容 丙", "内容 丁", "内容 戊");

        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new RuntimeException("injected mid-batch fault");
            }
            return invocation.callRealMethod();
        }).when(embeddingModel).call(any(EmbeddingRequest.class));

        assertThrows(VectorIndexException.class, () ->
                indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档", chunks));
        long partial = indexService.countPgForVersion(documentId, 1);
        assertTrue(partial >= 1 && partial < 5, "期望部分写入，实际=" + partial);

        reset(embeddingModel);
        VectorIndexResultView recovered = indexService.indexChunks(documentId, 1,
                "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1,
                        "内容 甲", "内容 乙", "内容 丙", "内容 丁", "内容 戊"));

        assertEquals(VectorIndexStatus.COMPLETED.name(), recovered.getStatus());
        assertEquals(5, indexService.countPgForVersion(documentId, 1));
        List<DocumentChunkIndexPO> relations = indexService.listChunks(documentId);
        assertEquals(5, relations.size());
        Set<String> ids = relations.stream()
                .map(DocumentChunkIndexPO::getChunkId).collect(Collectors.toSet());
        assertEquals(5, ids.size());
    }

    // DB 唯一约束：并发与重复防护 -----------------------------------------

    @Test
    void uniqueConstraint_rejectsDuplicateChunkId() {
        indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "内容 甲", "内容 乙"));
        String existingId = indexService.listChunks(documentId).get(0).getChunkId();

        // 用原生 INSERT（非 Upsert）验证 DB 唯一约束 uk_chunk_id：
        // 即使来自不同文档，只要 chunk_id 相同，数据库必须拒绝（MySQL 1062 → DuplicateKeyException）。
        String insertSql = """
                INSERT INTO document_chunk_index
                    (document_id, version, strategy, chunk_id, chunk_index, content,
                     chunk_content_hash, char_length, global_start, global_end,
                     status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String otherDoc = "d31vi-other-" + UUID.randomUUID().toString().substring(0, 6);
        LocalDateTime now = LocalDateTime.now();
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update(insertSql,
                otherDoc, 1, "STRUCTURE", existingId, 0, "不同内容",
                "x", 3, 0, 3, "INDEXED", now, now));
    }

    @Test
    void concurrentIndex_convergesToSingleSet() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<VectorIndexResultView> task = () -> indexService.indexChunks(documentId, 1,
                "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "内容 甲", "内容 乙", "内容 丙", "内容 丁"));
        try {
            List<Future<VectorIndexResultView>> futures = List.of(pool.submit(task), pool.submit(task));
            for (Future<VectorIndexResultView> future : futures) {
                assertDoesNotThrow(() -> future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(4, indexService.countPgForVersion(documentId, 1));
        assertEquals(4, indexService.listChunks(documentId).size());
    }

    // 核对与补偿 ----------------------------------------------------------

    @Test
    void reconcile_dryRunCleanSystemHasNoIssues() {
        indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "内容 甲", "内容 乙", "内容 丙"));
        ReconciliationReportView report = reconciliationService.reconcile(true);
        assertEquals(0, report.totalIssues());
        assertTrue(report.getActionsTaken().isEmpty());
    }

    @Test
    void reconcile_detectsOrphansAndCompensatingDeletes() {
        indexService.indexChunks(documentId, 1, "STRUCTURE", "测试文档",
                sampleChunks(documentId, 1, "内容 甲", "内容 乙", "内容 丙"));
        // 删除 MySQL 关联与状态，制造 PG 孤儿向量
        jdbcTemplate.update("DELETE FROM document_chunk_index WHERE document_id = ?", documentId);
        jdbcTemplate.update("DELETE FROM document_vector_index WHERE document_id = ?", documentId);

        ReconciliationReportView dryRun = reconciliationService.reconcile(true);
        assertEquals(3, dryRun.getOrphanVectors().size());
        assertTrue(dryRun.getActionsTaken().isEmpty());

        ReconciliationReportView fixed = reconciliationService.reconcile(false);
        assertEquals(3, fixed.getOrphanVectors().size());
        assertFalse(fixed.getActionsTaken().isEmpty());
        assertEquals(0, indexService.countPgForVersion(documentId, 1));
    }

    // 辅助 ---------------------------------------------------------------

    private List<Chunk> sampleChunks(String docId, int version, String... texts) {
        List<Chunk> chunks = new ArrayList<>(texts.length);
        for (int i = 0; i < texts.length; i++) {
            chunks.add(new Chunk(docId, "测试文档", version, ChunkStrategy.STRUCTURE,
                    i, texts[i], i * 10, i * 10 + texts[i].length(), new ArrayList<>()));
        }
        return chunks;
    }

    private String qualifiedTable() {
        return "\"" + pgProperties.getSchema() + "\".\"" + pgProperties.getTable() + "\"";
    }
}
