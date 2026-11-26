package com.zl.d43.hybrid.rag;

import com.zl.d43.hybrid.search.dto.FusionTraceEntry;
import com.zl.d43.rag.dto.RagAnswerResponse;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 混合检索 RAG 响应：继承共享 {@link RagAnswerResponse}（既有文件零修改），
 * 追加 hybrid 专属追溯字段。
 *
 * <p>语义边界：本链路的证据顺序为 RRF 融合顺序；{@code decision}/{@code errorCode} 等
 * 决策字段仍由共享决策链基于<b>向量路原始 similarity</b> 产生（关键词独有命中
 * similarity=null，按既有规则"无法判定时不做低相关阻断"处理，不伪造余弦值，§3.2/C09）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HybridRagAnswerResponse extends RagAnswerResponse {

    /** 检索策略标识。 */
    private String retrievalStrategy = "HYBRID_RRF";

    /** 检索层 matches.score 语义说明位（ask 的 similarity 字段仍为向量原始余弦或 null）。 */
    private String retrievalScoreSemantic = "RRF";

    /** 融合追溯（最终证据顺序对应的两路名次与贡献）。 */
    private List<FusionTraceEntry> fusionTrace;
}
