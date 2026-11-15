package com.zl.d32.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zl.d32.document.chunk.model.Chunk;
import com.zl.d32.document.chunk.model.ChunkStrategy;
import com.zl.d32.document.retrieval.SimilaritySearchService;
import com.zl.d32.document.retrieval.dto.SimilaritySearchRequest;
import com.zl.d32.document.retrieval.dto.SimilaritySearchResultView;
import com.zl.d32.document.vectorindex.service.DocumentVectorIndexService;
import com.zl.d32.document.vectorstore.support.EnabledIfPgVector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D32 十题评估数据采集工具（非默认 Surefire 用例，名称不含 Test 后缀）。
 *
 * <p>通过真实 D31 入库路径写入“评估用种子语料”，对 10 个真实业务问题各执行
 * Top-5 检索，把完整原始结果（真实 Chunk ID、真实分数、正文、元数据）序列化为
 * JSON 落盘，供后续人工相关性评估与文档引用。工具默认保留种子语料以便复核，
 * 清理方式见正式文档。</p>
 */
@EnabledIfPgVector
@SpringBootTest
class RetrievalEvaluationTool {

    static final String OUTPUT_JSON =
            "E:\\AI\\project\\AI-Learning-one\\M3-Java\\docs\\evidence\\D32\\D32-十题检索原始结果.json";

    @Autowired
    private SimilaritySearchService similaritySearchService;

    @Autowired
    private DocumentVectorIndexService indexService;

    @Autowired
    @Qualifier("pgVectorJdbcTemplate")
    private JdbcTemplate pgJdbc;

    private final List<String> documentIds = List.of(
            "d32eval-air", "d32eval-pump", "d32eval-code", "d32eval-model");

    @Test
    void collect() throws Exception {
        // 1) 干净起点：幂等删除后重新入库。
        for (String id : documentIds) {
            indexService.deleteByDocument(id);
        }
        ingest();

        // 2) 十题 Top-5 检索。
        List<String> questions = List.of(
                "空压机运行温度过高怎么处理",
                "空压机排气压力不足是什么原因",
                "水泵运行异响振动大怎么排查",
                "水泵机械密封漏水怎么办",
                "故障代码 E-101 代表什么",
                "故障代码 E-205 是什么意思",
                "设备型号 AC-380 是什么设备",
                "设备型号 WP-75 对应什么",
                "电机不启动应该派哪类工单",
                "空压机日常保养有哪些项目和周期");

        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            SimilaritySearchRequest request = new SimilaritySearchRequest();
            request.setQuery(questions.get(i));
            request.setTopK(5);
            SimilaritySearchResultView result = similaritySearchService.search(request);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("questionId", "Q" + (i + 1));
            record.put("question", questions.get(i));
            record.put("result", result);
            records.add(record);
        }

        // 3) 汇总并落盘。
        Long totalRows = pgJdbc.queryForObject(
                "SELECT COUNT(*) FROM public.d32_vector_store WHERE metadata->>'documentId' IN (?,?,?,?)",
                Long.class, documentIds.toArray());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("generatedAt", java.time.LocalDateTime.now().toString());
        envelope.put("table", "public.d32_vector_store");
        envelope.put("seedDocumentIds", documentIds);
        envelope.put("seedVectorRows", totalRows);
        envelope.put("topK", 5);
        envelope.put("records", records);

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        Path path = Path.of(OUTPUT_JSON);
        Files.createDirectories(path.getParent());
        Files.write(path, mapper.writeValueAsBytes(envelope));

        System.out.println("EVAL_DONE rows=" + totalRows + " questions=" + questions.size()
                + " output=" + OUTPUT_JSON);
    }

    private void ingest() {
        indexService.indexChunks("d32eval-air", 1, ChunkStrategy.STRUCTURE.name(),
                "空压机故障与维修手册", List.of(
                        chunk("d32eval-air", "空压机故障与维修手册", 0, "空压机运行温度过高（超温）故障，应先检查冷却器是否积灰堵塞、润滑油液位与油质、环境通风是否良好；处理步骤为清洗冷却器、补充或更换润滑油、改善通风。"),
                        chunk("d32eval-air", "空压机故障与维修手册", 1, "空压机排气压力不足故障，常见原因有进气阀未全开、管路或接头泄漏、压力开关设定值偏低、空气滤芯堵塞；应逐项检查并重新设定压力。"),
                        chunk("d32eval-air", "空压机故障与维修手册", 2, "空压机启动后立即停机，多由过载保护动作、电机接线松动、急停回路未复位引起；应检查热继电器、紧固接线并复位急停。"),
                        chunk("d32eval-air", "空压机故障与维修手册", 3, "空压机运行异响，常见于轴承磨损、联轴器松动、润滑油不足；应停机检查轴承与联轴器，必要时更换并紧固。"),
                        chunk("d32eval-air", "空压机故障与维修手册", 4, "空压机排水阀堵塞会使压缩空气含水升高，应定期清洗疏水阀、对储气罐排污并检查干燥器。"),
                        chunk("d32eval-air", "空压机故障与维修手册", 5, "空压机日常保养：每班次记录运行温度、排气压力与油位；按周期更换空气滤芯、油滤芯和润滑油，检查皮带与联轴器对中。")));

        indexService.indexChunks("d32eval-pump", 1, ChunkStrategy.STRUCTURE.name(),
                "工业水泵运行与维修手册", List.of(
                        chunk("d32eval-pump", "工业水泵运行与维修手册", 0, "水泵运行异响且振动大，常见原因为叶轮卡滞或损坏、轴承磨损、泵内进入异物、地脚螺栓松动；应停机拆解检查并清除异物、紧固地脚。"),
                        chunk("d32eval-pump", "工业水泵运行与维修手册", 1, "水泵出水压力不足，应检查叶轮磨损、机械密封泄漏、进水口漏气或堵塞、吸程是否过高；处理为更换叶轮或密封件、清理进水滤网。"),
                        chunk("d32eval-pump", "工业水泵运行与维修手册", 2, "水泵电机不启动，先确认三相电源与热继电器状态，再排查电机绕组、启动电容与控制回路；属于电气故障时应断电检修。"),
                        chunk("d32eval-pump", "工业水泵运行与维修手册", 3, "水泵机械密封漏水，需更换机械密封件，并核对轴套磨损、泵轴对中与密封安装方向。"),
                        chunk("d32eval-pump", "工业水泵运行与维修手册", 4, "水泵气蚀现象表现为噪声、振动与流量下降，多因吸入压力低于汽化压力；应降低吸程、增大入口管径或提高液位。"),
                        chunk("d32eval-pump", "工业水泵运行与维修手册", 5, "水泵日常保养：检查轴承温升与润滑、机械密封渗漏、联轴器对中，定期清理滤网并记录出入口压力。")));

        indexService.indexChunks("d32eval-code", 1, ChunkStrategy.STRUCTURE.name(),
                "设备故障代码对照表", List.of(
                        chunk("d32eval-code", "设备故障代码对照表", 0, "故障代码 E-101 表示空压机排气温度过高（超温报警），对应检查冷却器、润滑油与通风。"),
                        chunk("d32eval-code", "设备故障代码对照表", 1, "故障代码 E-102 表示空压机排气压力不足，对应检查进气阀、管路泄漏与压力开关。"),
                        chunk("d32eval-code", "设备故障代码对照表", 2, "故障代码 E-205 表示水泵电机过载，对应检查热继电器、叶轮卡滞与轴承。"),
                        chunk("d32eval-code", "设备故障代码对照表", 3, "故障代码 E-207 表示水泵机械密封漏水，对应更换机械密封并核对轴套。"),
                        chunk("d32eval-code", "设备故障代码对照表", 4, "故障代码 E-301 表示通讯故障（设备与监控系统连接中断），应检查通讯线缆与网关。"),
                        chunk("d32eval-code", "设备故障代码对照表", 5, "故障代码 E-302 表示电源缺相，应立即停机并检查供电与接触器。")));

        indexService.indexChunks("d32eval-model", 1, ChunkStrategy.STRUCTURE.name(),
                "设备型号与工单分类规范", List.of(
                        chunk("d32eval-model", "设备型号与工单分类规范", 0, "设备型号 AC-380 为螺杆式空压机，额定功率 38 千瓦，排气压力 0.8 兆帕。"),
                        chunk("d32eval-model", "设备型号与工单分类规范", 1, "设备型号 AC-220 为螺杆式空压机，额定功率 22 千瓦，适用于中小气量工况。"),
                        chunk("d32eval-model", "设备型号与工单分类规范", 2, "设备型号 WP-75 为离心式水泵，额定功率 75 千瓦，额定流量 100 立方米每小时。"),
                        chunk("d32eval-model", "设备型号与工单分类规范", 3, "设备型号 WP-15 为立式管道泵，额定功率 15 千瓦，用于循环水输送。"),
                        chunk("d32eval-model", "设备型号与工单分类规范", 4, "工单分类：设备机械故障（如轴承、叶轮、密封损坏）派机械维修工单；电机不启动、缺相、过载等电气问题派电气维修工单。"),
                        chunk("d32eval-model", "设备型号与工单分类规范", 5, "工单分类：超温、压力不足等工艺参数异常派工艺巡检工单；通讯故障派自动化仪表工单；日常保养派预防性维护工单。")));
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
