package com.zl.d42.hybrid.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 混合检索实验配置（前缀 {@code app.hybrid}）。
 *
 * <p><b>H2 硬契约</b>：{@code enabled} 默认 false 且不加 matchIfMissing——缺省即关闭，
 * 关闭时本类与全部 hybrid Bean 不装配，应用与 HEAD 行为完全一致。</p>
 *
 * <p>参数值与 {@code docs/evidence/D42/hybrid/probe/hybrid-params.json} 冻结值一一对应；
 * 实现与评测一律引用冻结值（H6：禁止看结果后调参）。</p>
 */
@Data
@Component
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "app.hybrid")
public class HybridProperties {

    /** 总开关（false 时 hybrid 全套 Bean 不装配）。 */
    private boolean enabled = false;

    /** 关键词特征子集（冻结值 w+g；c 单字符停用，理由见任务书 §3.4）。 */
    private List<String> featureSubset = List.of("w", "g");

    /** BM25 词频饱和参数 k1（冻结 1.2，Robertson & Zaragoza 2009）。 */
    private double bm25K1 = 1.2d;

    /** BM25 长度归一化参数 b（冻结 0.75）。 */
    private double bm25B = 0.75d;

    /** RRF 平滑常数 k（冻结 60，Cormack et al. SIGIR 2009 经验值）。 */
    private int rrfK = 60;

    /** 每路候选数 candidateTopN（冻结 10；防御性钳制 1..20）。 */
    private int candidateTopN = 10;

    /** 倒排加载的 PG schema（与 local 向量表同源，只读）。 */
    private String schema = "public";

    /** 倒排加载的 PG 表（默认 d42_vector_store，只读零 DDL）。 */
    private String table = "d42_vector_store";

    /**
     * 语料规范指纹（P0/KP2 冻结）：构建期复算不等则 CORPUS_DRIFT fail-fast；
     * 空串表示跳过断言（仅限本地试验，正式取证必须注入冻结值）。
     */
    private String expectedCollectionFingerprint = "";
}
