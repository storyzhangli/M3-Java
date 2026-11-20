package com.zl.d37.rag.context;

import com.zl.d37.document.retrieval.dto.SimilarityMatchView;
import com.zl.d37.rag.citation.SnippetExtractor;
import com.zl.d37.rag.config.RagProperties;
import com.zl.d37.rag.context.ContextAssemblyResult;
import com.zl.d37.rag.context.EvidenceItem;
import com.zl.d37.rag.dto.RagBudgetView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文组装服务：把候选 Chunk 处理为模型可用的证据上下文。
 *
 * <p>职责闭环：</p>
 * <ol>
 *   <li>有效性检查：排除空内容/无稳定 ID 的记录；</li>
 *   <li>按稳定 Chunk ID 去重，保留首次出现位置；</li>
 *   <li>保留检索顺序（命中已按距离升序/相似度降序，不做重排序）；</li>
 *   <li>字符预算控制：按检索顺序纳入，超预算即停止并记录，不静默破坏证据；</li>
 *   <li>为每个证据分配唯一来源标识 [S1]…，并映射真实 Chunk/document 元数据。</li>
 * </ol>
 *
 * <p><b>预算口径</b>：单位为 UTF-16 字符（code unit），非 Token；本工程无 Tokenizer，
 * 不伪称 Token，估算误差在预算说明中注明。</p>
 */
@Slf4j
@Service
public class ContextAssemblyService {

    private static final String REASON_INVALID = "INVALID";
    private static final String REASON_DEDUP = "DEDUP";
    private static final String REASON_BUDGET = "BUDGET";

    private final RagProperties ragProperties;

    public ContextAssemblyService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 组装上下文。
     *
     * @param candidates 检索候选（按检索顺序）
     * @return 组装结果（永不返回 null）
     */
    public ContextAssemblyResult assemble(List<SimilarityMatchView> candidates) {
        List<ContextAssemblyResult.Excluded> preExcluded = new ArrayList<>();
        Map<String, SimilarityMatchView> unique = new LinkedHashMap<>();

        // 1) 有效性检查 + 2) 去重（保留检索顺序）
        if (candidates != null) {
            for (SimilarityMatchView candidate : candidates) {
                if (!isValid(candidate)) {
                    preExcluded.add(new ContextAssemblyResult.Excluded(
                            candidate == null ? "unknown" : safeId(candidate.getChunkId()),
                            REASON_INVALID));
                    continue;
                }
                if (unique.containsKey(candidate.getChunkId())) {
                    preExcluded.add(new ContextAssemblyResult.Excluded(
                            candidate.getChunkId(), REASON_DEDUP));
                    continue;
                }
                unique.put(candidate.getChunkId(), candidate);
            }
        }

        // 3) 预算控制 + 来源标识
        int budget = ragProperties.getContextBudgetChars();
        StringBuilder contextBuilder = new StringBuilder();
        List<EvidenceItem> evidence = new ArrayList<>();
        List<ContextAssemblyResult.Excluded> budgetExcluded = new ArrayList<>();
        boolean budgetReached = false;
        int sequence = 0;

        for (SimilarityMatchView match : unique.values()) {
            if (budgetReached) {
                budgetExcluded.add(new ContextAssemblyResult.Excluded(
                        match.getChunkId(), REASON_BUDGET));
                continue;
            }
            String sourceRef = "S" + (sequence + 1);
            String block = renderBlock(sourceRef, match);
            if (contextBuilder.length() + block.length() > budget) {
                budgetReached = true;
                budgetExcluded.add(new ContextAssemblyResult.Excluded(
                        match.getChunkId(), REASON_BUDGET));
                continue;
            }
            contextBuilder.append(block);
            evidence.add(toEvidence(sourceRef, match, block.length()));
            sequence++;
        }

        ContextAssemblyResult result = new ContextAssemblyResult();
        result.setContextText(contextBuilder.toString());
        result.setEvidence(evidence);

        List<ContextAssemblyResult.Excluded> allExcluded = new ArrayList<>(preExcluded.size()
                + budgetExcluded.size());
        allExcluded.addAll(preExcluded);
        allExcluded.addAll(budgetExcluded);
        result.setExcluded(allExcluded);
        result.setBudget(buildBudgetView(budget, contextBuilder.length(), evidence.size(),
                budgetExcluded.size(), preExcluded.size()));

        log.info("上下文组装完成: included={}, budgetExcluded={}, invalidOrDedup={}, usedChars={}/{}",
                evidence.size(), budgetExcluded.size(), preExcluded.size(),
                contextBuilder.length(), budget);
        return result;
    }

    /**
     * 渲染单个证据块：以不可信数据边界包裹，明确其中任何指令都不得执行。
     */
    private String renderBlock(String sourceRef, SimilarityMatchView match) {
        return "<UNTRUSTED_EVIDENCE id=\"" + sourceRef + "\">\n"
                + "来源：" + nullSafe(match.getTitle())
                + " (documentId=" + nullSafe(match.getDocumentId())
                + ", version=" + match.getVersion()
                + ", chunkIndex=" + match.getChunkIndex() + ")\n"
                + "正文：" + nullSafe(match.getContent()) + "\n"
                + "</UNTRUSTED_EVIDENCE>\n\n";
    }

    private EvidenceItem toEvidence(String sourceRef, SimilarityMatchView match, int blockLength) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceRef(sourceRef);
        item.setChunkId(match.getChunkId());
        item.setContent(match.getContent());
        item.setDocumentId(match.getDocumentId());
        item.setTitle(match.getTitle());
        item.setVersion(match.getVersion());
        item.setChunkIndex(match.getChunkIndex());
        item.setStrategy(match.getStrategy());
        item.setSimilarity(match.getSimilarity());
        item.setDistance(match.getDistance());
        item.setPage(match.getPage());
        item.setLastPage(match.getLastPage());
        item.setParagraphIndex(match.getParagraphIndex());
        // 引用片段只从真实命中内容截取，模型不参与；上限可配置，默认 200 UTF-16 字符。
        item.setQuotedSnippet(SnippetExtractor.extract(
                match.getContent(), ragProperties.getQuotedSnippetMaxChars()));
        item.setBlockLength(blockLength);
        return item;
    }

    private RagBudgetView buildBudgetView(int budget, int used, int included,
                                          int budgetExcluded, int invalidOrDedup) {
        RagBudgetView view = new RagBudgetView();
        view.setBudgetUnit(ragProperties.getBudgetUnit());
        view.setBudgetLimit(budget);
        view.setEstimatedUsed(used);
        view.setIncludedChunks(included);
        view.setBudgetExcludedChunks(budgetExcluded);
        view.setInvalidOrDedupChunks(invalidOrDedup);
        view.setEstimationNote("单位为 UTF-16 字符(code unit)，非 Token；本工程无 Tokenizer，"
                + "采用字符长度保守估算，真实 Token 数随分词器变化，可能存在偏差，未伪称 Token。");
        return view;
    }

    private boolean isValid(SimilarityMatchView candidate) {
        return candidate != null
                && candidate.getChunkId() != null && !candidate.getChunkId().isBlank()
                && candidate.getContent() != null && !candidate.getContent().isBlank();
    }

    private String safeId(String id) {
        return id == null || id.isBlank() ? "unknown" : id;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
