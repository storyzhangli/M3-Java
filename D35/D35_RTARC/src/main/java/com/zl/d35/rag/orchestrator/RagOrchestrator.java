package com.zl.d35.rag.orchestrator;

import com.zl.d35.rag.assessment.AssessmentDecision;
import com.zl.d35.rag.assessment.EvidenceAssessmentResult;
import com.zl.d35.rag.assessment.EvidenceAssessmentService;
import com.zl.d35.rag.context.ContextAssemblyResult;
import com.zl.d35.rag.context.EvidenceItem;
import com.zl.d35.rag.dto.ConflictGroupView;
import com.zl.d35.rag.dto.CitationValidationView;
import com.zl.d35.rag.dto.RagAnswerResponse;
import com.zl.d35.rag.dto.RagQuestionRequest;
import com.zl.d35.rag.dto.RagSourceView;
import com.zl.d35.rag.dto.RagTimingView;
import com.zl.d35.rag.generation.GenerationResult;
import com.zl.d35.rag.generation.GenerationService;
import com.zl.d35.rag.context.ContextAssemblyService;
import com.zl.d35.rag.model.GenerationStatus;
import com.zl.d35.rag.model.RagErrorCode;
import com.zl.d35.rag.model.RetrievalStatus;
import com.zl.d35.rag.retrieval.RagRetrievalQuery;
import com.zl.d35.rag.retrieval.RagRetrievalResult;
import com.zl.d35.rag.retrieval.RagRetrievalService;
import com.zl.d35.rag.validation.CitationValidationResult;
import com.zl.d35.rag.validation.ResponseValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 编排器：协调完整链路，不承载检索/模型调用细节。
 *
 * <p>固定流程：校验问题 → 检索 → 上下文组装 → 证据评估 →
 * （仅 ANSWER 才调用生成）→ 生成后校验 → 汇总回答/来源/澄清/拒答/冲突。</p>
 *
 * <p>关键边界：检索失败、无证据、澄清、拒答、冲突均不调用 ChatModel，并保留可核验的零调用证据；
 * 生成后校验未通过时受控返回 GENERATION_VALIDATION_FAILED，不直接放行。</p>
 */
@Slf4j
@Service
public class RagOrchestrator {

    private static final String NO_EVIDENCE_ANSWER =
            "知识库中未检索到可用于回答该问题的有效证据，已停止生成，未调用大模型。";

    private final RagRetrievalService retrievalService;
    private final ContextAssemblyService contextAssemblyService;
    private final EvidenceAssessmentService evidenceAssessmentService;
    private final GenerationService generationService;
    private final ResponseValidationService responseValidationService;

    public RagOrchestrator(RagRetrievalService retrievalService,
                           ContextAssemblyService contextAssemblyService,
                           EvidenceAssessmentService evidenceAssessmentService,
                           GenerationService generationService,
                           ResponseValidationService responseValidationService) {
        this.retrievalService = retrievalService;
        this.contextAssemblyService = contextAssemblyService;
        this.evidenceAssessmentService = evidenceAssessmentService;
        this.generationService = generationService;
        this.responseValidationService = responseValidationService;
    }

    /**
     * 执行一次 RAG 问答。
     *
     * @param request 已通过校验的请求
     * @return 完整响应
     */
    public RagAnswerResponse ask(RagQuestionRequest request) {
        long totalStart = System.nanoTime();
        String question = normalizeQuestion(request.getQuestion());

        // 1) 检索
        long retrievalStart = System.nanoTime();
        RagRetrievalResult retrieval = retrievalService.retrieve(new RagRetrievalQuery(
                question, request.getTopK(), request.getDocumentId(),
                request.getVersion(), request.getStrategy(), request.getTitle()));
        long retrievalMs = elapsedMs(retrievalStart);

        // 2) 检索失败：短路，不组装、不生成
        if (retrieval.status() == RetrievalStatus.FAILED) {
            return buildRetrievalFailure(retrieval, retrievalMs, elapsedMs(totalStart));
        }

        // 3) 上下文组装
        long assemblyStart = System.nanoTime();
        ContextAssemblyResult context = contextAssemblyService.assemble(retrieval.matches());
        long assemblyMs = elapsedMs(assemblyStart);

        // 4) 无证据：拒答，不调用模型
        if (!context.hasEvidence()) {
            return buildNoEvidence(retrieval, context, retrievalMs, assemblyMs,
                    elapsedMs(totalStart));
        }

        // 5) 证据评估（不调用模型）
        EvidenceAssessmentResult assessment =
                evidenceAssessmentService.assess(context.getEvidence(), question);

        // 6) 非 ANSWER 决策：澄清/拒答/冲突，均不调用生成
        if (assessment.decision() != AssessmentDecision.ANSWER) {
            return buildControlledDecision(assessment, retrieval, context,
                    retrievalMs, assemblyMs, elapsedMs(totalStart));
        }

        // 7) 仅 ANSWER 才调用生成
        long generationStart = System.nanoTime();
        GenerationResult generation = generationService.generate(question, context);
        long generationMs = elapsedMs(generationStart);

        // 8) 生成后校验（引用合法性 + 事实支持）
        List<EvidenceItem> acceptedEvidence = filterAccepted(context, assessment);
        return buildResponse(retrieval, context, assessment, generation, acceptedEvidence,
                retrievalMs, assemblyMs, generationMs, elapsedMs(totalStart));
    }

    private RagAnswerResponse buildRetrievalFailure(RagRetrievalResult retrieval,
                                                    long retrievalMs, long totalMs) {
        RagAnswerResponse response = baseResponse();
        response.setRetrievalStatus(RetrievalStatus.FAILED);
        response.setGenerationStatus(GenerationStatus.NOT_ATTEMPTED);
        response.setErrorCode(RagErrorCode.RETRIEVAL_FAILED);
        response.setErrorMessage(retrieval.failureReason());
        response.setRetrievedCount(0);
        response.setTiming(timing(retrievalMs, 0L, 0L, totalMs));
        log.warn("RAG 受控返回：检索失败，未执行生成");
        return response;
    }

    private RagAnswerResponse buildNoEvidence(RagRetrievalResult retrieval,
                                              ContextAssemblyResult context,
                                              long retrievalMs, long assemblyMs, long totalMs) {
        RagAnswerResponse response = baseResponse();
        response.setAnswer(NO_EVIDENCE_ANSWER);
        response.setRetrievalStatus(retrieval.status());
        response.setGenerationStatus(GenerationStatus.SKIPPED_NO_EVIDENCE);
        response.setErrorCode(RagErrorCode.NO_EVIDENCE);
        response.setErrorMessage("无可用证据，已按约束跳过 ChatModel 调用");
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setEvidenceCount(0);
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, assemblyMs, 0L, totalMs));
        log.warn("RAG 受控返回：无可用证据，未调用 ChatModel");
        return response;
    }

    /**
     * 澄清/拒答/冲突受控决策：不调用生成，回填评估字段。
     */
    private RagAnswerResponse buildControlledDecision(EvidenceAssessmentResult assessment,
                                                      RagRetrievalResult retrieval,
                                                      ContextAssemblyResult context,
                                                      long retrievalMs, long assemblyMs,
                                                      long totalMs) {
        RagAnswerResponse response = baseResponse();
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
        log.warn("RAG 受控返回：评估决策={}，未调用 ChatModel", assessment.decision());
        return response;
    }

    private RagAnswerResponse buildResponse(RagRetrievalResult retrieval,
                                           ContextAssemblyResult context,
                                           EvidenceAssessmentResult assessment,
                                           GenerationResult generation,
                                           List<EvidenceItem> acceptedEvidence,
                                           long retrievalMs, long assemblyMs,
                                           long generationMs, long totalMs) {
        RagAnswerResponse response = baseResponse();
        response.setRetrievalStatus(retrieval.status());
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setSources(toSourceViews(context.getEvidence()));
        response.setEvidenceCount(context.getEvidence().size());
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, assemblyMs, generationMs, totalMs));
        applyAssessment(response, assessment);

        if (generation.status() != GenerationStatus.SUCCESS) {
            response.setGenerationStatus(generation.status());
            response.setErrorCode(generation.errorCode());
            response.setErrorMessage(generation.errorMessage());
            log.warn("RAG 受控返回：生成失败 status={} code={}",
                    generation.status(), generation.errorCode());
            return response;
        }

        // 生成后校验：未通过则受控失败，不直接放行模型文本
        CitationValidationResult validation =
                responseValidationService.validate(generation.content(), acceptedEvidence);
        response.setCitationValidation(toValidationView(validation));
        if (!validation.validationPassed()) {
            response.setAnswer(null);
            response.setGenerationStatus(GenerationStatus.FAILED_ERROR);
            response.setErrorCode(RagErrorCode.GENERATION_VALIDATION_FAILED);
            response.setErrorMessage("生成后校验未通过，已拦截输出，请补充权威证据后重试");
            log.warn("RAG 受控返回：生成后校验未通过 invalid={} unsupported={}",
                    validation.invalidCitations(), validation.unsupportedClaims());
            return response;
        }

        response.setAnswer(generation.content());
        response.setGenerationStatus(GenerationStatus.SUCCESS);
        response.setErrorCode(RagErrorCode.NONE);
        response.setUnknownCitationDetected(generation.unknownCitationDetected());
        log.info("RAG 流程结束: evidence={}, generation=SUCCESS", context.getEvidence().size());
        return response;
    }

    private void applyAssessment(RagAnswerResponse response, EvidenceAssessmentResult assessment) {
        response.setDecision(assessment.decision());
        response.setRejectedEvidenceIds(assessment.rejectedEvidenceIds());
        response.setMissingInformation(assessment.missingInformation());
        response.setConflictGroups(toConflictViews(assessment.conflictGroups()));
    }

    private List<EvidenceItem> filterAccepted(ContextAssemblyResult context,
                                              EvidenceAssessmentResult assessment) {
        List<EvidenceItem> accepted = new ArrayList<>();
        for (EvidenceItem item : context.getEvidence()) {
            if (assessment.acceptedEvidenceIds().contains(item.getSourceRef())) {
                accepted.add(item);
            }
        }
        return accepted;
    }

    private List<ConflictGroupView> toConflictViews(
            List<com.zl.d35.rag.assessment.ConflictGroup> groups) {
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
        view.setValidationPassed(result.validationPassed());
        return view;
    }

    private List<RagSourceView> toSourceViews(List<EvidenceItem> evidence) {
        List<RagSourceView> views = new ArrayList<>(evidence.size());
        for (EvidenceItem item : evidence) {
            RagSourceView view = new RagSourceView();
            view.setSourceRef(item.getSourceRef());
            view.setChunkId(item.getChunkId());
            view.setDocumentId(item.getDocumentId());
            view.setTitle(item.getTitle());
            view.setVersion(item.getVersion());
            view.setChunkIndex(item.getChunkIndex());
            view.setStrategy(item.getStrategy());
            view.setSimilarity(item.getSimilarity());
            view.setDistance(item.getDistance());
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

    private RagAnswerResponse baseResponse() {
        return new RagAnswerResponse();
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
