package com.zl.d35.rag.real;

import com.zl.d35.rag.citation.CitationVerificationService;
import com.zl.d35.rag.context.EvidenceItem;
import com.zl.d35.rag.model.RagErrorCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D35 真实基础设施测试（零 ChatModel 调用）。
 *
 * <p>仅当 {@code D35_REAL_INFRA=true} 时启用。使用真实 pgvector（入库/检索/真实分数）
 * 与真实 MySQL 链路，验证引用契约：384 维、稳定/请求级标识、页码缺失隐藏、
 * 多来源独立编号，以及删除竞态的响应前核验。全程不调用 ChatModel。</p>
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "D35_REAL_INFRA", matches = "true")
class RagRealInfrastructureTest extends RagRealTestSupport {

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgJdbcTemplate;

    @Autowired
    private com.zl.d35.rag.orchestrator.RagOrchestrator orchestrator;

    @Autowired
    private CitationVerificationService citationVerificationService;

    @BeforeAll
    void setUp() {
        ensureFixtures();
    }

    @AfterAll
    void tearDown() {
        cleanupFixtures();
    }

    @Test
    void vectorTableEmbeddingColumnIs384() {
        List<Map<String, Object>> rows = pgJdbcTemplate.queryForList("""
                SELECT a.attname AS name, format_type(a.atttypid, a.atttypmod) AS type
                FROM pg_attribute a
                         JOIN pg_class c ON a.relid = c.oid
                         JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'public'
                  AND c.relname = 'd35_vector_store'
                  AND a.attname = 'embedding'
                """);
        assertThat(rows).hasSize(1);
        String type = String.valueOf(rows.get(0).get("type"));
        System.out.println("[real-infra] embedding column type=" + type);
        assertThat(type).isEqualTo("vector(384)");
    }

    @Test
    void groundedAnswerCarriesSourceContract() {
        var response = orchestrator.ask(request("D35设备甲的额定压力是多少？", DOC_A, 5));
        System.out.println("[real-infra] decision=" + response.getDecision()
                + " code=" + response.getErrorCode() + " gen=" + response.getGenerationStatus());

        assertThat(response.getDecision()).isEqualTo(
                com.zl.d35.rag.assessment.AssessmentDecision.ANSWER);
        assertThat(response.getSources()).isNotEmpty();
        var src = response.getSources().get(0);
        assertThat(src.getSourceId()).isEqualTo(DOC_A);
        assertThat(src.getCitationId()).isEqualTo("S1");
        assertThat(src.getPage()).isEqualTo(2);
        assertThat(src.getQuotedSnippet()).isNotBlank();
        assertThat(src.getUrl()).isNull();
        // mock 模式无 ChatModel，证明未发生真实模型调用
        assertThat(response.getGenerationStatus())
                .isEqualTo(com.zl.d35.rag.model.GenerationStatus.NOT_CONFIGURED);
    }

    @Test
    void pageMissingHiddenNotFabricated() {
        var response = orchestrator.ask(request("D35设备丙的外壳是什么颜色？", DOC_C, 5));
        assertThat(response.getSources()).isNotEmpty();
        response.getSources().forEach(s -> {
            assertThat(s.getPage()).isNull();
            assertThat(s.getUrl()).isNull();
        });
    }

    @Test
    void multiSourceIndependentNumberingAndStableOrder() {
        var response = orchestrator.ask(request("D35设备乙的技术参数有哪些？", DOC_B, 5));
        assertThat(response.getSources()).hasSize(3);

        List<String> refs = response.getSources().stream()
                .map(s -> s.getCitationId()).toList();
        assertThat(refs).containsExactly("S1", "S2", "S3");

        List<String> chunkIds = response.getSources().stream()
                .map(s -> s.getChunkId()).toList();
        assertThat(chunkIds).doesNotHaveDuplicates();

        List<String> snippets = response.getSources().stream()
                .map(s -> s.getQuotedSnippet()).toList();
        assertThat(snippets).doesNotHaveDuplicates();
    }

    @Test
    void deletionRaceMarkedCitationTargetDeleted() {
        // 取真实 chunkId 构造证据，再删除该文档，模拟检索/生成期间的删除竞态
        String realChunkId = indexService.listChunks(DOC_DEL).get(0).getChunkId();
        EvidenceItem evidence = new EvidenceItem();
        evidence.setSourceRef("S1");
        evidence.setDocumentId(DOC_DEL);
        evidence.setChunkId(realChunkId);
        evidence.setContent("额定压力 1.0");
        evidence.setTitle(TITLE_DEL);

        indexService.deleteByDocument(DOC_DEL);
        documentMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                com.zl.d35.document.po.KnowledgeDocumentPO>()
                .eq("document_id", DOC_DEL));

        var result = citationVerificationService.verify(List.of(evidence));

        assertThat(result.validItems()).isEmpty();
        assertThat(result.hasRemoval()).isTrue();
        assertThat(result.removed().get(0).reasonCode())
                .isEqualTo(RagErrorCode.CITATION_TARGET_DELETED);
    }
}
