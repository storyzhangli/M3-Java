package com.zl.d40.one.rag;

import com.zl.d40.rag.assessment.AssessmentDecision;
import com.zl.d40.rag.assessment.EvidenceAssessmentResult;
import com.zl.d40.rag.assessment.EvidenceAssessmentService;
import com.zl.d40.rag.citation.CitationVerificationService;
import com.zl.d40.rag.context.ContextAssemblyResult;
import com.zl.d40.rag.context.ContextAssemblyService;
import com.zl.d40.rag.context.EvidenceItem;
import com.zl.d40.rag.generation.GenerationResult;
import com.zl.d40.rag.generation.GenerationService;
import com.zl.d40.rag.model.GenerationStatus;
import com.zl.d40.rag.model.RagErrorCode;
import com.zl.d40.rag.model.RetrievalStatus;
import com.zl.d40.rag.retrieval.RagRetrievalQuery;
import com.zl.d40.rag.retrieval.RagRetrievalResult;
import com.zl.d40.rag.validation.CitationValidationResult;
import com.zl.d40.rag.validation.ResponseValidationService;
import com.zl.d40.rag.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * one_ 轨 RAG 编排器：流程与 local 轨 {@code RagOrchestrator} 完全对齐，
 * 唯一差异是检索源替换为 {@link OneRagRetrievalService}（one_ 1024 向量空间）。
 *
 * <p>共享 Bean 只读复用、零修改：ContextAssemblyService、EvidenceAssessmentService、
 * GenerationService、ResponseValidationService、CitationVerificationService（含 UrlSafetyPolicy）
 * 均与 Embedding 模型无关。响应契约复用 {@link RagAnswerResponse}：
 * sourceId=documentId 稳定、citationId=S1/S2 请求级、quotedSnippet≤200、url 恒 null。</p>
 *
 * <p>决策链（与 local 一致）：生命周期筛选(仅 ACTIVE,fail-closed)→CONFLICT→NO_EVIDENCE
 * →LOW_RELEVANCE→CLARIFY→INSUFFICIENT_EVIDENCE→ANSWER；仅 ANSWER 才调用生成。
 * AI_MODE=mock 下生成态为 NOT_CONFIGURED/MODEL_NOT_CONFIGURED 受控态；
 * 删除竞态 CITATION_TARGET_DELETED 在 mock 下不可观测，如实标 N/A 不伪造。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.one.embedding", name = "enabled", havingValue = "true")
public class OneRagOrchestrator {

    private static final String NO_EVIDENCE_ANSWER =
            "知识库中未检索到可用于回答该问题的有效证据，已停止生成，未调用大模型。";

    private final OneRagRetrievalService retrievalService;
    private final ContextAssemblyService contextAssemblyService;
    private final EvidenceAssessmentService evidenceAssessmentService;
    private final GenerationService generationService;
    private final ResponseValidationService responseValidationService;
    private final CitationVerificationService citationVerificationService;

    public OneRagOrchestrator(OneRagRetrievalService retrievalService,
                              ContextAssemblyService contextAssemblyService,
                              EvidenceAssessmentService evidenceAssessmentService,
                              GenerationService generationService,
                              ResponseValidationService responseValidationService,
                              CitationVerificationService citationVerificationService) {
        this.retrievalService = retrievalService;
        this.contextAssemblyService = contextAssemblyService;
        this.evidenceAssessmentService = evidenceAssessmentService;
        this.generationService = generationService;
        this.responseValidationService = responseValidationService;
        this.citationVerificationService = citationVerificationService;
    }

    /** 执行一次 one_ 轨 RAG 问答。 */
    public RagAnswerResponse ask(RagQuestionRequest request) {
        long totalStart = System.nanoTime();
        String question = normalizeQuestion(request.getQuestion());

        long retrievalStart = System.nanoTime();
        RagRetrievalResult retrieval = retrievalService.retrieve(new RagRetrievalQuery(
                question, request.getTopK(), request.getDocumentId(),
                request.getVersion(), request.getStrategy(), request.getTitle()));
        long retrievalMs = elapsedMs(retrievalStart);

        if (retrieval.status() == RetrievalStatus.FAILED) {
            return buildRetrievalFailure(retrieval, retrievalMs, elapsedMs(totalStart));
        }

        long assemblyStart = System.nanoTime();
        ContextAssemblyResult context = contextAssemblyService.assemble(retrieval.matches());
        long assemblyMs = elapsedMs(assemblyStart);

        if (!context.hasEvidence()) {
            return buildNoEvidence(retrieval, context, retrievalMs, assemblyMs,
                    elapsedMs(totalStart));
        }

        EvidenceAssessmentResult assessment =
                evidenceAssessmentService.assess(context.getEvidence(), question);

        if (assessment.decision() != AssessmentDecision.ANSWER) {
            return buildControlledDecision(assessment, retrieval, context,
                    retrievalMs, assemblyMs, elapsedMs(totalStart));
        }

        long generationStart = System.nanoTime();
        GenerationResult generation = generationService.generate(question, context);
        long generationMs = elapsedMs(generationStart);

        CitationVerificationService.CitationVerification verification =
                citationVerificationService.verify(context.getEvidence());

        return buildResponse(retrieval, context, assessment, generation, verification,
                retrievalMs, assemblyMs, generationMs, elapsedMs(totalStart));
    }

    private RagAnswerResponse buildRetrievalFailure(RagRetrievalResult retrieval,
                                                    long retrievalMs, long totalMs) {
        RagAnswerResponse response = new RagAnswerResponse();
        response.setRetrievalStatus(RetrievalStatus.FAILED);
        response.setGenerationStatus(GenerationStatus.NOT_ATTEMPTED);
        response.setErrorCode(RagErrorCode.RETRIEVAL_FAILED);
        response.setErrorMessage(retrieval.failureReason());
        response.setRetrievedCount(0);
        response.setTiming(timing(retrievalMs, 0L, 0L, totalMs));
        log.warn("one_ RAG 受控返回：检索失败，未执行生成");
        return response;
    }

    private RagAnswerResponse buildNoEvidence(RagRetrievalResult retrieval,
                                              ContextAssemblyResult context,
                                              long retrievalMs, long assemblyMs, long totalMs) {
        RagAnswerResponse response = new RagAnswerResponse();
        response.setAnswer(NO_EVIDENCE_ANSWER);
        response.setRetrievalStatus(retrieval.status());
        response.setGenerationStatus(GenerationStatus.SKIPPED_NO_EVIDENCE);
        response.setErrorCode(RagErrorCode.NO_EVIDENCE);
        response.setErrorMessage("无可用证据，已按约束跳过 ChatModel 调用");
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setEvidenceCount(0);
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, assemblyMs, 0L, totalMs));
        log.warn("one_ RAG 受控返回：无可用证据，未调用 ChatModel");
        return response;
    }

    private RagAnswerResponse buildControlledDecision(EvidenceAssessmentResult assessment,
                                                      RagRetrievalResult retrieval,
                                                      ContextAssemblyResult context,
                                                      long retrievalMs, long assemblyMs,
                                                      long totalMs) {
        RagAnswerResponse response = new RagAnswerResponse();
        response.setRetrievalStatus(retrieval.status());
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setEvidenceCount(context.getEvidence().size());
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, assemblyMs, 0L, totalMs));
        applyAssessment(response, assessment);

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
        log.warn("one_ RAG 受控返回：评估决策={}，未调用 ChatModel", assessment.decision());
        return response;
    }

    private RagAnswerResponse buildResponse(
            RagRetrievalResult retrieval,
            ContextAssemblyResult context,
            EvidenceAssessmentResult assessment,
            GenerationResult generation,
            CitationVerificationService.CitationVerification verification,
            long retrievalMs, long assemblyMs,
            long generationMs, long totalMs) {
        RagAnswerResponse response = new RagAnswerResponse();
        response.setRetrievalStatus(retrieval.status());
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, assemblyMs, generationMs, totalMs));
        applyAssessment(response, assessment);

        if (generation.status() != GenerationStatus.SUCCESS) {
            response.setGenerationStatus(generation.status());
            response.setErrorCode(generation.errorCode());
            response.setErrorMessage(generation.errorMessage());
            response.setSources(toSourceViews(context.getEvidence()));
            response.setEvidenceCount(context.getEvidence().size());
            log.warn("one_ RAG 受控返回：生成失败 status={} code={}",
                    generation.status(), generation.errorCode());
            return response;
        }

        List<EvidenceItem> validItems = verification.validItems();

        if (validItems.isEmpty()) {
            response.setAnswer(null);
            response.setGenerationStatus(GenerationStatus.FAILED_ERROR);
            response.setErrorCode(selectRemovalCode(verification));
            response.setErrorMessage("引用来源在返回前已全部删除或失效，已停止返回该回答。");
            response.setSources(List.of());
            response.setEvidenceCount(0);
            response.setCitationNotes(buildCitationNotes(verification, List.of()));
            log.warn("one_ RAG 受控返回：响应前来源全部失效 code={}", response.getErrorCode());
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
            log.warn("one_ RAG 受控返回：生成后校验未通过 invalid={} unsupported={} fabricatedUrl={}",
                    validation.invalidCitations(), validation.unsupportedClaims(),
                    validation.fabricatedUrls());
            return response;
        }

        response.setAnswer(generation.content());
        response.setGenerationStatus(GenerationStatus.SUCCESS);
        response.setErrorCode(RagErrorCode.NONE);
        response.setUnknownCitationDetected(generation.unknownCitationDetected());
        log.info("one_ RAG 流程结束: evidence={}, valid={}, generation=SUCCESS",
                context.getEvidence().size(), validItems.size());
        return response;
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

    private void applyAssessment(RagAnswerResponse response, EvidenceAssessmentResult assessment) {
        response.setDecision(assessment.decision());
        response.setRejectedEvidenceIds(assessment.rejectedEvidenceIds());
        response.setMissingInformation(assessment.missingInformation());
        response.setConflictGroups(toConflictViews(assessment.conflictGroups()));
    }

    private List<ConflictGroupView> toConflictViews(
            List<com.zl.d40.rag.assessment.ConflictGroup> groups) {
        List<ConflictGroupView> views = new ArrayList<>();
        for (var g : groups) {
            ConflictGroupView view = new ConflictGroupView();
            view.setSubject(g.subject());
            view.setParameter(g.parameter());
            view.setValues(g.values());
            view.setEvidenceIds(g.evidenceIds());
            views.add(view);
        }
        return views;
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

    private RagTimingView timing(long retrievalMs, long assemblyMs,
                                 long generationMs, long totalMs) {
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