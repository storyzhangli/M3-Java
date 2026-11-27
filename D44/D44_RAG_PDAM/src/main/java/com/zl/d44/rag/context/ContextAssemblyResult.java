package com.zl.d44.rag.context;

import com.zl.d44.rag.context.EvidenceItem;
import com.zl.d44.rag.dto.RagBudgetView;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组装结果：最终上下文文本、选中证据、剔除记录与预算统计。
 */
@Data
public class ContextAssemblyResult {

    /** 最终渲染的上下文文本（含 [S1] 等来源标记），长度不超过预算。 */
    private String contextText;

    /** 选中证据（保留检索顺序）。 */
    private List<EvidenceItem> evidence = new ArrayList<>();

    /** 被剔除片段及原因（INVALID/DEDUP/BUDGET）。 */
    private List<Excluded> excluded = new ArrayList<>();

    /** 预算统计。 */
    private RagBudgetView budget;

    public boolean hasEvidence() {
        return evidence != null && !evidence.isEmpty();
    }

    /**
     * 被剔除片段记录。
     *
     * @param chunkId 被剔除 Chunk 标识
     * @param reason  剔除原因（INVALID/DEDUP/BUDGET）
     */
    public record Excluded(String chunkId, String reason) {
    }
}
