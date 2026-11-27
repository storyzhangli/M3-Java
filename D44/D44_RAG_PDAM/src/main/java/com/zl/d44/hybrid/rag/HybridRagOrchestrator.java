package com.zl.d44.hybrid.rag;

import com.zl.d44.document.retrieval.dto.SimilarityMatchView;
import com.zl.d44.hybrid.rag.HybridRagAnswerResponse;
import com.zl.d44.hybrid.search.HybridSearchService;
import com.zl.d44.hybrid.search.dto.HybridSearchRequest;
import com.zl.d44.rag.assessment.AssessmentDecision;
import com.zl.d44.rag.assessment.EvidenceAssessmentResult;
import com.zl.d44.rag.assessment.EvidenceAssessmentService;
import com.zl.d44.rag.citation.CitationVerificationService;
import com.zl.d44.rag.context.ContextAssemblyResult;
import com.zl.d44.rag.context.ContextAssemblyService;
import com.zl.d44.rag.context.EvidenceItem;
import com.zl.d44.rag.dto.*;
import com.zl.d44.rag.generation.GenerationResult;
import com.zl.d44.rag.generation.GenerationService;
import com.zl.d44.rag.model.GenerationStatus;
import com.zl.d44.rag.model.RagErrorCode;
import com.zl.d44.rag.model.RetrievalStatus;
import com.zl.d44.rag.validation.CitationValidationResult;
import com.zl.d44.rag.validation.ResponseValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索 RAG 编排器：检索源替换为 {@link HybridSearchService}，
 * 其余链路（上下文组装 → 证据评估 → 生成 → 权威核验 → 生成后校验）
 * <b>只读复用共享 Bean，零修改</b>，保证与 local ask 唯一变量=检索策略（H3）。
 *
 * <p>固定流程与受控短路语义对齐 {@code RagOrchestrator}：检索失败、无证据、澄清、
 * 拒答、冲突均不调用 ChatModel；离线 mock 模式生成端行为与共享链一致。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.hybrid", name = "enabled", havingValue = "true")
public class HybridRagOrchestrator {

    private static final String NO_EVIDENCE_ANSWER =
            "知识库中未检索到可用于回答该问题的有效证据，已停止生成，未调用大模型。";

    private final HybridSearchService hybridSearchService;
    private final ContextAssemblyService contextAssemblyService;
    private final EvidenceAssessmentService evidenceAssessmentService;
    private final GenerationService generationService;
    private final ResponseValidationService responseValidationService;
    private final CitationVerificationService citationVerificationService;

    public HybridRagOrchestrator(
            @Qualifier("hybridSearchService") HybridSearchService hybridSearchService,
            @Qualifier("contextAssemblyService") ContextAssemblyService contextAssemblyService,
            @Qualifier("evidenceAssessmentService") EvidenceAssessmentService evidenceAssessmentService,
            @Qualifier("generationService") GenerationService generationService,
            @Qualifier("responseValidationService") ResponseValidationService responseValidationService,
            @Qualifier("citationVerificationService") CitationVerificationService citationVerificationService) {
        this.hybridSearchService = hybridSearchService;
        this.contextAssemblyService = contextAssemblyService;
        this.evidenceAssessmentService = evidenceAssessmentService;
        this.generationService = generationService;
        this.responseValidationService = responseValidationService;
        this.citationVerificationService = citationVerificationService;
    }

    public HybridRagAnswerResponse ask(RagQuestionRequest request) {
        long totalStart = System.nanoTime();
        String question = normalizeQuestion(request.getQuestion());

        // 1) 混合检索（失败与空结果严格区分；检索层异常不吞栈，仅外显通用原因）
        long retrievalStart = System.nanoTime();
        HybridSearchService.HybridSearchBundle bundle;
        try {
            bundle = hybridSearchService.search(buildSearchRequest(request, question));
        } catch (RuntimeException ex) {
            HybridRagAnswerResponse failed = baseResponse();
            failed.setRetrievalStatus(RetrievalStatus.FAILED);
            failed.setGenerationStatus(GenerationStatus.NOT_ATTEMPTED);
            failed.setErrorCode(RagErrorCode.RETRIEVAL_FAILED);
            failed.setErrorMessage("混合检索执行失败，请稍后重试");
            failed.setRetrievedCount(0);
            failed.setTiming(timing(elapsedMs(retrievalStart), 0L, 0L, elapsedMs(totalStart)));
            log.error("hybrid RAG 受控返回：检索失败，异常类型={}", ex.getClass().getSimpleName());
            return failed;
        }
        long retrievalMs = elapsedMs(retrievalStart);
        List<SimilarityMatchView> fusedMatches = bundle.orderedEvidenceMatches();

        RetrievalStatus retrievalStatus = fusedMatches.isEmpty()
                ? RetrievalStatus.EMPTY : RetrievalStatus.SUCCESS;

        // 2) 上下文组装（顺序=RRF 融合顺序；similarity 为向量路原值或 null）
        long assemblyStart = System.nanoTime();
        ContextAssemblyResult context = contextAssemblyService.assemble(fusedMatches);
        long assemblyMs = elapsedMs(assemblyStart);

        HybridRagAnswerResponse response = baseResponse();
        response.setFusionTrace(bundle.view().getFusionTrace());
        response.setRetrievalStatus(retrievalStatus);
        response.setRetrievedCount(fusedMatches.size());

        // 3) 无证据：拒答，不调用模型
        if (!context.hasEvidence()) {
            response.setAnswer(NO_EVIDENCE_ANSWER);
            response.setGenerationStatus(GenerationStatus.SKIPPED_NO_EVIDENCE);
            response.setErrorCode(RagErrorCode.NO_EVIDENCE);
            response.setErrorMessage("无可用证据，已按约束跳过 ChatModel 调用");
            response.setEvidenceCount(0);
            response.setBudget(context.getBudget());
            response.setTiming(timing(retrievalMs, assemblyMs, 0L, elapsedMs(totalStart)));
            log.warn("hybrid RAG 受控返回：无可用证据，未调用 ChatModel");
            return response;
        }

        // 4) 证据评估（共享决策链，不调模型）
        EvidenceAssessmentResult assessment =
                evidenceAssessmentService.assess(context.getEvidence(), question);
        applyAssessment(response, assessment);
        response.setEvidenceCount(context.getEvidence().size());
        response.setBudget(context.getBudget());

        // 5) 非 ANSWER：澄清/拒答/冲突均不调用生成
        if (assessment.decision() != AssessmentDecision.ANSWER) {
            response.setTiming(timing(retrievalMs, assemblyMs, 0L, elapsedMs(totalStart)));
            switch (assessment.decision()) {
                case CLARIFY -> {
                    response.setAnswer(null);
                    response.setGenerationStatus(GenerationStatus.CLARIFY);
                    response.setErrorCode(RagErrorCode.MISSING_REQUIRED_CONTEXT);
                    response.setErrorMessage(assessment.reason());
                    response.setClarificationQuestion(assessment.reason());
                }
                case CONFLICT -> {
                    response.setAnswer(null);
                    response.setGenerationStatus(GenerationStatus.CONFLICT);
                    response.setErrorCode(RagErrorCode.EVIDENCE_CONFLICT);
                    response.setErrorMessage(assessment.reason());
                }
                default -> {
                    response.setAnswer(assessment.reason());
                    response.setGenerationStatus(GenerationStatus.SKIPPED_NO_EVIDENCE);
                    response.setErrorCode(assessment.reasonCode());
                    response.setErrorMessage(assessment.reason());
                }
            }
            log.warn("hybrid RAG 受控返回：评估决策={}", assessment.decision());
            return response;
        }

        // 6) 仅 ANSWER 才调用生成（共享 GenerationService，mock 模式零外网）
        long generationStart = System.nanoTime();
        GenerationResult generation = generationService.generate(question, context);
        long generationMs = elapsedMs(generationStart);
        response.setTiming(timing(retrievalMs, assemblyMs, generationMs, elapsedMs(totalStart)));

        if (generation.status() != GenerationStatus.SUCCESS) {
            response.setGenerationStatus(generation.status());
            response.setErrorCode(generation.errorCode());
            response.setErrorMessage(generation.errorMessage());
            response.setSources(toSourceViews(context.getEvidence()));
            log.warn("hybrid RAG 受控返回：生成失败 status={} code={}",
                    generation.status(), generation.errorCode());
            return response;
        }

        // 7) 响应前权威核验（共享批量核验，覆盖删除/失效竞态）
        CitationVerificationService.CitationVerification verification =
                citationVerificationService.verify(context.getEvidence());
        List<EvidenceItem> validItems = verification.validItems();

        if (validItems.isEmpty()) {
            response.setAnswer(null);
            response.setGenerationStatus(GenerationStatus.FAILED_ERROR);
            response.setErrorCode(selectRemovalCode(verification));
            response.setErrorMessage("引用来源在返回前已全部删除或失效，已停止返回该回答。");
            response.setSources(List.of());
            response.setEvidenceCount(0);
            response.setCitationNotes(buildCitationNotes(verification, List.of()));
            log.warn("hybrid RAG 受控返回：响应前来源全部失效 code={}", response.getErrorCode());
            return response;
        }

        citationVerificationService.markCited(generation.content(), validItems);
        CitationValidationResult validation =
                responseValidationService.validate(generation.content(), validItems);
        response.setCitationValidation(toValidationView(validation));
        response.setSources(toSourceViews(validItems));
        response.setEvidenceCount(validItems.size());
        response.setCitationNotes(buildCitationNotes(verification, validItems));

        if (!validation.validationPassed()) {
            response.setAnswer(null);
            response.setGenerationStatus(GenerationStatus.FAILED_ERROR);
            if (!validation.fabricatedUrls().isEmpty()) {
                response.setErrorCode(RagErrorCode.FABRICATED_URL);
                response.setErrorMessage("回答包含危险协议或无来源支撑的链接，已受控拦截。");
            } else {
                response.setErrorCode(RagErrorCode.GENERATION_VALIDATION_FAILED);
                response.setErrorMessage("生成后校验未通过，已拦截输出，请补充权威证据后重试");
            }
            log.warn("hybrid RAG 受控返回：生成后校验未通过 invalid={} unsupported={} fabricatedUrl={}",
                    validation.invalidCitations(), validation.unsupportedClaims(),
                    validation.fabricatedUrls());
            return response;
        }

        response.setAnswer(generation.content());
        response.setGenerationStatus(GenerationStatus.SUCCESS);
        response.setErrorCode(RagErrorCode.NONE);
        response.setUnknownCitationDetected(generation.unknownCitationDetected());
        log.info("hybrid RAG 流程结束: evidence={}, valid={}",
                context.getEvidence().size(), validItems.size());
        return response;
    }

    private HybridSearchRequest buildSearchRequest(RagQuestionRequest request, String question) {
        HybridSearchRequest searchRequest = new HybridSearchRequest();
        searchRequest.setQuery(question);
        searchRequest.setTopK(request.getTopK());
        searchRequest.setDocumentId(request.getDocumentId());
        searchRequest.setVersion(request.getVersion());
        searchRequest.setStrategy(request.getStrategy());
        searchRequest.setTitle(request.getTitle());
        return searchRequest;
    }

    private HybridRagAnswerResponse baseResponse() {
        return new HybridRagAnswerResponse();
    }

    private void applyAssessment(HybridRagAnswerResponse response, EvidenceAssessmentResult assessment) {
        response.setDecision(assessment.decision());
        response.setRejectedEvidenceIds(assessment.rejectedEvidenceIds());
        response.setMissingInformation(assessment.missingInformation());
        List<ConflictGroupView> views = new ArrayList<>();
        for (var group : assessment.conflictGroups()) {
            ConflictGroupView view = new ConflictGroupView();
            view.setSubject(group.subject());
            view.setParameter(group.parameter());
            view.setValues(group.values());
            view.setEvidenceIds(group.evidenceIds());
            views.add(view);
        }
        response.setConflictGroups(views);
    }

    private CitationValidationView toValidationView(CitationValidationResult result) {
        CitationValidationView view = new CitationValidationView();
        view.setLegalCitationPassed(result.legalCitationPassed());
        view.setInvalidCitations(result.invalidCitations());
        view.setUnsupportedClaims(result.unsupportedClaims());
        view.setClaimCitationMismatches(result.claimCitationMismatches());
        view.setFabricatedUrls(result.fabricatedUrls());
        view.setValidationPassed(result.validationPassed());
        return view;
    }

    private List<RagSourceView> toSourceViews(List<EvidenceItem> evidence) {
        List<RagSourceView> views = new ArrayList<>(evidence.size());
        for (EvidenceItem item : evidence) {
            RagSourceView view = new RagSourceView();
            view.setSourceId(item.getDocumentId());
            view.setCitationId(item.getSourceRef());
            view.setSourceRef(item.getSourceRef());
            view.setChunkId(item.getChunkId());
            view.setDocumentId(item.getDocumentId());
            view.setTitle(item.getTitle());
            view.setVersion(item.getVersion());
            view.setChunkIndex(item.getChunkIndex());
            view.setPage(item.getPage());
            view.setLastPage(item.getLastPage());
            view.setParagraphIndex(item.getParagraphIndex());
            view.setQuotedSnippet(item.getQuotedSnippet());
            view.setCited(item.isCited());
            view.setStrategy(item.getStrategy());
            view.setSimilarity(item.getSimilarity());
            view.setDistance(item.getDistance());
            view.setUrl(citationVerificationService.resolveUrl(null));
            views.add(view);
        }
        return views;
    }

    private List<String> buildCitationNotes(
            CitationVerificationService.CitationVerification verification,
            List<EvidenceItem> validItems) {
        List<String> notes = new ArrayList<>();
        for (CitationVerificationService.RemovedSource removed : verification.removed()) {
            notes.add(removed.sourceRef() + " 已剔除：" + removed.reasonCode()
                    + "（" + removed.reason() + "）");
        }
        long withPage = validItems.stream().filter(item -> item.getPage() != null).count();
        if (withPage < validItems.size()) {
            notes.add("页码可溯源 " + withPage + "/" + validItems.size()
                    + "，缺失来源 page 保持 null（PAGE_LOCATION_MISSING，不补造）");
        }
        return notes;
    }

    private RagErrorCode selectRemovalCode(
            CitationVerificationService.CitationVerification verification) {
        List<RagErrorCode> codes = verification.removed().stream()
                .map(CitationVerificationService.RemovedSource::reasonCode)
                .toList();
        if (codes.contains(RagErrorCode.CITATION_TARGET_DELETED)) {
            return RagErrorCode.CITATION_TARGET_DELETED;
        }
        if (codes.contains(RagErrorCode.CITATION_OWNERSHIP_MISMATCH)) {
            return RagErrorCode.CITATION_OWNERSHIP_MISMATCH;
        }
        return RagErrorCode.EVIDENCE_NOT_EFFECTIVE;
    }

    private RagTimingView timing(long retrievalMs, long assemblyMs, long generationMs, long totalMs) {
        RagTimingView view = new RagTimingView();
        view.setRetrievalMs(retrievalMs);
        view.setAssemblyMs(assemblyMs);
        view.setGenerationMs(generationMs);
        view.setTotalMs(totalMs);
        return view;
    }

    private String normalizeQuestion(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        return raw.trim();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
