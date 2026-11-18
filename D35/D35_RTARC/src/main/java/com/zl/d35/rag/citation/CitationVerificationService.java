package com.zl.d35.rag.citation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zl.d35.document.mapper.DocumentChunkIndexMapper;
import com.zl.d35.document.mapper.KnowledgeDocumentMapper;
import com.zl.d35.document.model.DocumentLifecycleStatus;
import com.zl.d35.document.po.DocumentChunkIndexPO;
import com.zl.d35.document.po.KnowledgeDocumentPO;
import com.zl.d35.rag.context.EvidenceItem;
import com.zl.d35.rag.model.RagErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用权威核验服务：在回答返回前，对本轮证据做批量状态核验与 cited 标记。
 *
 * <p>与生成前的 {@code EvidenceAssessmentService} 是两道独立关口：后者在调用大模型前
 * 评估证据充分性，本服务负责覆盖“检索/生成期间文档被删除或失效”的竞态窗口，并在
 * 响应前以权威持久化数据为准剔除不可返回来源。</p>
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
        Set<String> existingChunkIds = loadChunks(safeEvidence);

        List<EvidenceItem> valid = new ArrayList<>();
        List<RemovedSource> removed = new ArrayList<>();
        Map<String, RagErrorCode> reasonByRef = new LinkedHashMap<>();

        for (EvidenceItem item : safeEvidence) {
            RagErrorCode reason = classify(item, docMap, existingChunkIds);
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
                                  Set<String> existingChunkIds) {
        KnowledgeDocumentPO doc = item.getDocumentId() == null
                ? null : docMap.get(item.getDocumentId());
        if (doc == null) {
            return RagErrorCode.CITATION_TARGET_DELETED;
        }
        if (item.getChunkId() == null || !existingChunkIds.contains(item.getChunkId())) {
            return RagErrorCode.CITATION_TARGET_DELETED;
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

    private Set<String> loadChunks(List<EvidenceItem> evidence) {
        Set<String> chunkIds = new LinkedHashSet<>();
        for (EvidenceItem item : evidence) {
            if (item.getChunkId() != null && !item.getChunkId().isBlank()) {
                chunkIds.add(item.getChunkId());
            }
        }
        if (chunkIds.isEmpty()) {
            return Set.of();
        }
        List<DocumentChunkIndexPO> rows = chunkMapper.selectList(
                new QueryWrapper<DocumentChunkIndexPO>().in("chunk_id", chunkIds));
        Set<String> set = new HashSet<>();
        for (DocumentChunkIndexPO po : rows) {
            set.add(po.getChunkId());
        }
        return set;
    }
}
