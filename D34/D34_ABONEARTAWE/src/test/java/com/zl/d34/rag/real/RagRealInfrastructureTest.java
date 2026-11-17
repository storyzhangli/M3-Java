package com.zl.d34.rag.real;

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
 * D34 真实基础设施测试（零模型调用）。
 *
 * <p>仅当 {@code D34_REAL_INFRA=true} 时启用。使用真实 pgvector（入库/检索/真实分数）
 * 与真实 MySQL 索引链路，验证四类受控决策，全程不调用 ChatModel：</p>
 * <ul>
 *   <li>无证据 → REFUSE / NO_EVIDENCE；</li>
 *   <li>低相关 → REFUSE / LOW_RELEVANCE；</li>
 *   <li>冲突 → CONFLICT / EVIDENCE_CONFLICT；</li>
 *   <li>接地证据 → ANSWER（mock 模式下生成状态 NOT_CONFIGURED，证明未发生真实模型调用）。</li>
 * </ul>
 */
@SpringBootTest(properties = "app.rag.assessment.low-relevance-similarity-threshold=0.25")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "D34_REAL_INFRA", matches = "true")
class RagRealInfrastructureTest extends RagRealTestSupport {

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgJdbcTemplate;

    @Autowired
    private com.zl.d34.rag.orchestrator.RagOrchestrator orchestrator;

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
                         JOIN pg_class c ON a.attrelid = c.oid
                         JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'public'
                  AND c.relname = 'd34_vector_store'
                  AND a.attname = 'embedding'
                """);
        assertThat(rows).hasSize(1);
        String type = String.valueOf(rows.get(0).get("type"));
        System.out.println("[real-infra] embedding column type=" + type);
        assertThat(type).isEqualTo("vector(384)");
    }

    @Test
    void noEvidenceRefusesWithoutModel() {
        var response = orchestrator.ask(request("L5缺失设备的额定压力是多少？", DOC_MISSING, 5));
        System.out.println("[real-infra] no-evidence decision=" + response.getDecision()
                + " errorCode=" + response.getErrorCode() + " gen=" + response.getGenerationStatus());
        // 无证据在评估前短路返回，decision 未设置；以错误码与生成状态为准
        assertThat(response.getDecision()).isNull();
        assertThat(response.getErrorCode()).isEqualTo(com.zl.d34.rag.model.RagErrorCode.NO_EVIDENCE);
        assertThat(response.getGenerationStatus())
                .isEqualTo(com.zl.d34.rag.model.GenerationStatus.SKIPPED_NO_EVIDENCE);
    }

    @Test
    void lowRelevanceRefusesWithoutModel() {
        var response = orchestrator.ask(request("L5设备丙的额定压力是多少？", DOC_C, 5));
        System.out.println("[real-infra] low-relevance decision=" + response.getDecision()
                + " errorCode=" + response.getErrorCode() + " gen=" + response.getGenerationStatus());
        response.getSources().forEach(s -> System.out.println(
                "[real-infra] low-relevance score=" + s.getSimilarity() + " distance=" + s.getDistance()));
        assertThat(response.getDecision()).isEqualTo(com.zl.d34.rag.assessment.AssessmentDecision.REFUSE);
        assertThat(response.getErrorCode()).isEqualTo(com.zl.d34.rag.model.RagErrorCode.LOW_RELEVANCE);
    }

    @Test
    void conflictDetectedWithoutModel() {
        var response = orchestrator.ask(request("L5设备乙的额定压力是多少？", DOC_B, 5));
        System.out.println("[real-infra] conflict decision=" + response.getDecision()
                + " errorCode=" + response.getErrorCode() + " groups=" + response.getConflictGroups().size());
        assertThat(response.getDecision()).isEqualTo(com.zl.d34.rag.assessment.AssessmentDecision.CONFLICT);
        assertThat(response.getErrorCode()).isEqualTo(com.zl.d34.rag.model.RagErrorCode.EVIDENCE_CONFLICT);
        assertThat(response.getConflictGroups()).hasSize(1);
        assertThat(response.getGenerationStatus())
                .isEqualTo(com.zl.d34.rag.model.GenerationStatus.CONFLICT);
    }

    @Test
    void groundedEvidenceDecidesAnswerWithoutModelCall() {
        var response = orchestrator.ask(request("L5设备甲的额定压力是多少？", DOC_A, 5));
        System.out.println("[real-infra] grounded decision=" + response.getDecision()
                + " errorCode=" + response.getErrorCode() + " gen=" + response.getGenerationStatus());
        response.getSources().forEach(s -> System.out.println(
                "[real-infra] grounded score=" + s.getSimilarity() + " distance=" + s.getDistance()));
        assertThat(response.getDecision()).isEqualTo(com.zl.d34.rag.assessment.AssessmentDecision.ANSWER);
        assertThat(response.getRetrievedCount()).isGreaterThan(0);
        // mock 模式无 ChatModel，证明本测试未发生真实模型调用
        assertThat(response.getGenerationStatus())
                .isEqualTo(com.zl.d34.rag.model.GenerationStatus.NOT_CONFIGURED);
    }
}
