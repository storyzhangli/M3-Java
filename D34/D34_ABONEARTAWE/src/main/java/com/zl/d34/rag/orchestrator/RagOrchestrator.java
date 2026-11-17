package com.zl.d34.rag.orchestrator;

import com.zl.d34.rag.context.ContextAssemblyResult;
import com.zl.d34.rag.context.EvidenceItem;
import com.zl.d34.rag.dto.RagAnswerResponse;
import com.zl.d34.rag.dto.RagQuestionRequest;
import com.zl.d34.rag.dto.RagSourceView;
import com.zl.d34.rag.dto.RagTimingView;
import com.zl.d34.rag.generation.GenerationResult;
import com.zl.d34.rag.generation.GenerationService;
import com.zl.d34.rag.context.ContextAssemblyService;
import com.zl.d34.rag.model.GenerationStatus;
import com.zl.d34.rag.model.RagErrorCode;
import com.zl.d34.rag.model.RetrievalStatus;
import com.zl.d34.rag.retrieval.RagRetrievalQuery;
import com.zl.d34.rag.retrieval.RagRetrievalResult;
import com.zl.d34.rag.retrieval.RagRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 编排器：协调完整最小闭环，不承载检索/模型调用细节。
 *
 * <p>固定流程：校验问题 → 检索 → 状态判断 → 上下文组装 → 证据判断 →
 * （有证据才调用生成）→ 汇总回答/来源/各阶段状态。</p>
 *
 * <p>关键边界：检索失败、无证据（含空检索）、生成失败分别返回不同状态；
 * 无证据时<b>不调用</b> GenerationService/ChatModel。</p>
 */
@Slf4j
@Service
public class RagOrchestrator {

    private static final String NO_EVIDENCE_ANSWER =
            "知识库中未检索到可用于回答该问题的有效证据，已停止生成，未调用大模型。";

    private final RagRetrievalService retrievalService;
    private final ContextAssemblyService contextAssemblyService;
    private final GenerationService generationService;

    public RagOrchestrator(RagRetrievalService retrievalService,
                           ContextAssemblyService contextAssemblyService,
                           GenerationService generationService) {
        this.retrievalService = retrievalService;
        this.contextAssemblyService = contextAssemblyService;
        this.generationService = generationService;
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

        // 2) 检索失败：直接受控返回，不进入组装与生成
        if (retrieval.status() == RetrievalStatus.FAILED) {
            return buildRetrievalFailure(retrieval, retrievalMs,
                    elapsedMs(totalStart));
        }

        // 3) 上下文组装
        long assemblyStart = System.nanoTime();
        ContextAssemblyResult context = contextAssemblyService.assemble(retrieval.matches());
        long assemblyMs = elapsedMs(assemblyStart);

        // 4) 无可用证据（含空检索）：受控拒答，不调用生成/ChatModel
        if (!context.hasEvidence()) {
            return buildNoEvidence(retrieval, context, retrievalMs, assemblyMs,
                    elapsedMs(totalStart));
        }

        // 5) 有证据才调用生成
        long generationStart = System.nanoTime();
        GenerationResult generation = generationService.generate(question, context);
        long generationMs = elapsedMs(generationStart);

        // 6) 汇总
        return buildResponse(question, retrieval, context, generation,
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

    private RagAnswerResponse buildResponse(String question,
                                           RagRetrievalResult retrieval,
                                           ContextAssemblyResult context,
                                           GenerationResult generation,
                                           long retrievalMs, long assemblyMs,
                                           long generationMs, long totalMs) {
        RagAnswerResponse response = baseResponse();
        response.setRetrievalStatus(retrieval.status());
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setSources(toSourceViews(context.getEvidence()));
        response.setEvidenceCount(context.getEvidence().size());
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, assemblyMs, generationMs, totalMs));
        response.setUnknownCitationDetected(generation.unknownCitationDetected());

        if (generation.status() == GenerationStatus.SUCCESS) {
            response.setAnswer(generation.content());
            response.setGenerationStatus(GenerationStatus.SUCCESS);
            response.setErrorCode(RagErrorCode.NONE);
        } else {
            response.setGenerationStatus(generation.status());
            response.setErrorCode(generation.errorCode());
            response.setErrorMessage(generation.errorMessage());
            log.warn("RAG 受控返回：生成失败 status={} code={}",
                    generation.status(), generation.errorCode());
        }
        log.info("RAG 流程结束: questionLength={}, evidence={}, generation={}",
                question.length(), context.getEvidence().size(), generation.status());
        return response;
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
