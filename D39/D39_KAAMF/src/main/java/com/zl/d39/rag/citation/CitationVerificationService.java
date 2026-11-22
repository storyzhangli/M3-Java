package com.zl.d39.rag.citation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d39.document.mapper.DocumentChunkIndexMapper;
import com.zl.d39.document.mapper.KnowledgeDocumentMapper;
import com.zl.d39.document.model.DocumentLifecycleStatus;
import com.zl.d39.document.po.DocumentChunkIndexPO;
import com.zl.d39.document.po.KnowledgeDocumentPO;
import com.zl.d39.rag.citation.UrlSafetyPolicy;
import com.zl.d39.rag.context.EvidenceItem;
import com.zl.d39.rag.model.RagErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用权威核验服务：在回答返回前，对本轮证据做批量状态核验与 cited 标记。
 *
 * <p>与生成前的 {@code EvidenceAssessmentService} 是两道独立关口：后者在调用大模型前
 * 评估证据充分性，本服务负责覆盖“检索/生成期间文档被删除或失效”的竞态窗口，并在
 * 响应前以权威持久化数据为准剔除不可返回来源。</p>
 *
 * <p><b>归属校验（P1-01）</b>：文档与 Chunk 分别存在并不等于引用有效。Chunk 行自带
 * {@code documentId}，必须与 EvidenceItem 声明的 {@code documentId} 完全一致；不一致或
 * 归属无法确认（Chunk 行 documentId 为空、或同 chunkId 出现多个归属）时，一律剔除，
 * 避免把 DOC-B 的正文与 DOC-A 的标题、版本、页码组合返回。</p>
 *
 * <p>性能口径：仅执行两条批量 IN 查询（document_id、chunk_id），不做逐条 N+1。</p>
 */
@Slf4j
@Service
public class CitationVerificationService {

    /** 模型可生成的引用标记，仅用于标记 cited，不用于创造来源。 */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[S(\\d{1,3})]");

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentChunkIndexMapper chunkMapper;

    public CitationVerificationService(KnowledgeDocumentMapper documentMapper,
                                       DocumentChunkIndexMapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    /** 被剔除来源及其原因。 */
    public record RemovedSource(String sourceRef, String documentId, String chunkId,
                                RagErrorCode reasonCode, String reason) {
    }

    /** 批量核验结果。 */
    public record CitationVerification(List<EvidenceItem> validItems,
                                       List<RemovedSource> removed,
                                       Map<String, RagErrorCode> reasonByRef) {

        public boolean hasRemoval() {
            return !removed.isEmpty();
        }
    }

    /**
     * 批量核验证据。
     *
     * @param evidence 本轮进入上下文的证据
     * @return 核验结果（永不返回 null）
     */
    public CitationVerification verify(List<EvidenceItem> evidence) {
        List<EvidenceItem> safeEvidence = evidence == null ? List.of() : evidence;
        Map<String, KnowledgeDocumentPO> docMap = loadDocuments(safeEvidence);
        Map<String, String> chunkOwners = loadChunkOwners(safeEvidence);

        List<EvidenceItem> valid = new ArrayList<>();
        List<RemovedSource> removed = new ArrayList<>();
        Map<String, RagErrorCode> reasonByRef = new LinkedHashMap<>();

        for (EvidenceItem item : safeEvidence) {
            RagErrorCode reason = classify(item, docMap, chunkOwners);
            if (reason == RagErrorCode.NONE) {
                valid.add(item);
                reasonByRef.put(item.getSourceRef(), RagErrorCode.NONE);
            } else {
                String detail = reasonText(reason);
                removed.add(new RemovedSource(item.getSourceRef(), item.getDocumentId(),
                        item.getChunkId(), reason, detail));
                reasonByRef.put(item.getSourceRef(), reason);
            }
        }
        log.info("引用权威核验完成: total={}, valid={}, removed={}",
                safeEvidence.size(), valid.size(), removed.size());
        return new CitationVerification(List.copyOf(valid), List.copyOf(removed),
                Map.copyOf(reasonByRef));
    }

    /**
     * 按回答中真实出现的引用标记回填 cited；只标记已核验有效的来源。
     *
     * @param answer 模型回答
     * @param validItems 已核验有效的证据
     */
    public void markCited(String answer, List<EvidenceItem> validItems) {
        if (answer == null || validItems == null) {
            return;
        }
        Set<String> citedRefs = new HashSet<>();
        Matcher matcher = CITATION_MARKER.matcher(answer);
        while (matcher.find()) {
            citedRefs.add("S" + matcher.group(1));
        }
        for (EvidenceItem item : validItems) {
            item.setCited(citedRefs.contains(item.getSourceRef()));
        }
    }

    /**
     * 解析可返回 URL：仅当真实元数据提供且通过安全策略时返回，否则恒为 null。
     * 当前工程无授权外链来源，因此始终返回 null。
     */
    public String resolveUrl(String metadataUrl) {
        return UrlSafetyPolicy.sanitize(metadataUrl);
    }

    private RagErrorCode classify(EvidenceItem item,
                                  Map<String, KnowledgeDocumentPO> docMap,
                                  Map<String, String> chunkOwners) {
        KnowledgeDocumentPO doc = item.getDocumentId() == null
                ? null : docMap.get(item.getDocumentId());
        if (doc == null) {
            return RagErrorCode.CITATION_TARGET_DELETED;
        }
        if (item.getChunkId() == null || item.getChunkId().isBlank()
                || !chunkOwners.containsKey(item.getChunkId())) {
            return RagErrorCode.CITATION_TARGET_DELETED;
        }
        // P1-01：文档存在、Chunk 也存在，仍须证明该 Chunk 属于该文档。
        String actualDocumentId = chunkOwners.get(item.getChunkId());
        if (actualDocumentId == null || actualDocumentId.isBlank()) {
            return RagErrorCode.CITATION_OWNERSHIP_MISMATCH;
        }
        if (!actualDocumentId.equals(item.getDocumentId())) {
            return RagErrorCode.CITATION_OWNERSHIP_MISMATCH;
        }
        DocumentLifecycleStatus lifecycle =
                DocumentLifecycleStatus.fromName(doc.getLifecycleStatus());
        if (!lifecycle.isEffective()) {
            return RagErrorCode.EVIDENCE_NOT_EFFECTIVE;
        }
        return RagErrorCode.NONE;
    }

    private String reasonText(RagErrorCode code) {
        return switch (code) {
            case CITATION_TARGET_DELETED -> "目标文档或 Chunk 已被删除，响应前剔除";
            case CITATION_OWNERSHIP_MISMATCH -> "Chunk 归属与证据声明的 documentId 不一致或无法证明，响应前剔除";
            case EVIDENCE_NOT_EFFECTIVE -> "目标文档未生效或已废弃，响应前剔除";
            default -> "来源不可返回";
        };
    }

    private Map<String, KnowledgeDocumentPO> loadDocuments(List<EvidenceItem> evidence) {
        Set<String> docIds = new LinkedHashSet<>();
        for (EvidenceItem item : evidence) {
            if (item.getDocumentId() != null && !item.getDocumentId().isBlank()) {
                docIds.add(item.getDocumentId());
            }
        }
        if (docIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeDocumentPO> rows = documentMapper.selectList(
                new QueryWrapper<KnowledgeDocumentPO>().in("document_id", docIds));
        Map<String, KnowledgeDocumentPO> map = new LinkedHashMap<>();
        for (KnowledgeDocumentPO po : rows) {
            map.put(po.getDocumentId(), po);
        }
        return map;
    }

    /**
     * 批量加载 Chunk 真实归属：一次 IN 查询得到 {@code chunkId -> documentId}。
     *
     * <p>同一 chunkId 若出现多条归属不同的行，视为归属无法确认，映射为 {@code null}，
     * 由 {@link #classify} 以 {@link RagErrorCode#CITATION_OWNERSHIP_MISMATCH} 剔除。</p>
     */
    private Map<String, String> loadChunkOwners(List<EvidenceItem> evidence) {
        Set<String> chunkIds = new LinkedHashSet<>();
        for (EvidenceItem item : evidence) {
            if (item.getChunkId() != null && !item.getChunkId().isBlank()) {
                chunkIds.add(item.getChunkId());
            }
        }
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        List<DocumentChunkIndexPO> rows = chunkMapper.selectList(
                new QueryWrapper<DocumentChunkIndexPO>().in("chunk_id", chunkIds));

        Map<String, Set<String>> ownersByChunk = new LinkedHashMap<>();
        for (DocumentChunkIndexPO po : rows) {
            String documentId = po.getDocumentId();
            ownersByChunk.computeIfAbsent(po.getChunkId(), key -> new LinkedHashSet<>())
                    .add(documentId == null ? "" : documentId);
        }
        Map<String, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : ownersByChunk.entrySet()) {
            owners.put(entry.getKey(),
                    entry.getValue().size() == 1 ? entry.getValue().iterator().next() : null);
        }
        return owners;
    }
}
