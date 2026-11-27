package com.zl.d44.rag.orchestrator;

import com.zl.d44.rag.assessment.AssessmentDecision;
import com.zl.d44.rag.assessment.EvidenceAssessmentResult;
import com.zl.d44.rag.assessment.EvidenceAssessmentService;
import com.zl.d44.rag.citation.CitationVerificationService;
import com.zl.d44.document.retrieval.dto.SimilarityMatchView;
import com.zl.d44.rag.context.ContextAssemblyResult;
import com.zl.d44.rag.context.ContextAssemblyService;
import com.zl.d44.rag.context.EvidenceItem;
import com.zl.d44.rag.dto.*;
import com.zl.d44.rag.generation.GenerationResult;
import com.zl.d44.rag.generation.GenerationService;
import com.zl.d44.rag.model.GenerationStatus;
import com.zl.d44.rag.model.RagErrorCode;
import com.zl.d44.rag.model.RetrievalStatus;
import com.zl.d44.rag.rerank.RerankCandidate;
import com.zl.d44.rag.rerank.RerankFlags;
import com.zl.d44.rag.rerank.RerankPort;
import com.zl.d44.rag.rerank.RerankResult;
import com.zl.d44.rag.rerank.RerankUsage;
import com.zl.d44.rag.retrieval.RagRetrievalQuery;
import com.zl.d44.rag.retrieval.RagRetrievalResult;
import com.zl.d44.rag.retrieval.RagRetrievalService;
import com.zl.d44.rag.validation.CitationValidationResult;
import com.zl.d44.rag.validation.ResponseValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 编排器：协调完整链路，不承载检索/模型调用细节。
 *
 * <p>固定流程：校验问题 → 检索 → 上下文组装 → 证据评估 →
 * （仅 ANSWER 才调用生成）→ 响应前权威核验 → 生成后校验 → 汇总回答/来源/澄清/拒答/冲突。</p>
 *
 * <p>关键边界：检索失败、无证据、澄清、拒答、冲突均不调用 ChatModel，并保留可核验的零调用证据；
 * 引用元数据只来自本轮真实检索与权威持久化数据，模型只能生成引用标记，不能创造来源、页码、
 * 片段或 URL；响应前批量核验剔除删除/失效来源，全部失效则受控失败。</p>
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
    private final CitationVerificationService citationVerificationService;

    /**
     * 追加（D1 挂点）：可选精排端口。disabled（默认）时 Bean 缺席，
     * {@code getIfAvailable()} 返回 null → 原序直通，链路逐字节等价（H1）；
     * 存在时作用于<b>已经权限过滤</b>的检索结果（预门禁在 RagRetrievalService 之外，
     * 本步绝不 bypass 谓词、绝不把 FAILED/EMPTY 语义改掉，H7/§3.3）。
     */
    private final ObjectProvider<RerankPort> rerankPortProvider;

    public RagOrchestrator(RagRetrievalService retrievalService,
                           ContextAssemblyService contextAssemblyService,
                           EvidenceAssessmentService evidenceAssessmentService,
                           GenerationService generationService,
                           ResponseValidationService responseValidationService,
                           CitationVerificationService citationVerificationService,
                           ObjectProvider<RerankPort> rerankPortProvider) {
        this.retrievalService = retrievalService;
        this.contextAssemblyService = contextAssemblyService;
        this.evidenceAssessmentService = evidenceAssessmentService;
        this.generationService = generationService;
        this.responseValidationService = responseValidationService;
        this.citationVerificationService = citationVerificationService;
        this.rerankPortProvider = rerankPortProvider;
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

        // 2.5) 追加：检索后精排（仅 SUCCESS 且端口装配时执行；缺席/失败=原序，
        //      FAILED/EMPTY 分支语义未触碰；任何异常不外抛——H8 fail-open）
        long rerankStart = System.nanoTime();
        RerankResult rerank = applyRerank(question, retrieval.matches());
        long rerankMs = elapsedMs(rerankStart);
        List<SimilarityMatchView> orderedMatches = applyRerankOrder(retrieval.matches(), rerank);

        // 3) 上下文组装
        long assemblyStart = System.nanoTime();
        ContextAssemblyResult context = contextAssemblyService.assemble(orderedMatches);
        long assemblyMs = elapsedMs(assemblyStart);

        // 4) 无证据：拒答，不调用模型
        if (!context.hasEvidence()) {
            return buildNoEvidence(retrieval, context, retrievalMs, rerankMs, assemblyMs,
                    elapsedMs(totalStart), rerank);
        }

        // 5) 证据评估（不调用模型）
        EvidenceAssessmentResult assessment =
                evidenceAssessmentService.assess(context.getEvidence(), question);

        // 6) 非 ANSWER 决策：澄清/拒答/冲突，均不调用生成
        if (assessment.decision() != AssessmentDecision.ANSWER) {
            return buildControlledDecision(assessment, retrieval, context,
                    retrievalMs, rerankMs, assemblyMs, elapsedMs(totalStart), rerank);
        }

        // 7) 仅 ANSWER 才调用生成
        long generationStart = System.nanoTime();
        GenerationResult generation = generationService.generate(question, context);
        long generationMs = elapsedMs(generationStart);

        // 8) 响应前权威核验（批量，覆盖检索/生成期间的删除与失效竞态）
        CitationVerificationService.CitationVerification verification =
                citationVerificationService.verify(context.getEvidence());

        return buildResponse(retrieval, context, assessment, generation, verification,
                retrievalMs, rerankMs, assemblyMs, generationMs, elapsedMs(totalStart), rerank);
    }

    private RagAnswerResponse buildRetrievalFailure(RagRetrievalResult retrieval,
                                                    long retrievalMs, long totalMs) {
        RagAnswerResponse response = baseResponse();
        response.setRetrievalStatus(RetrievalStatus.FAILED);
        response.setGenerationStatus(GenerationStatus.NOT_ATTEMPTED);
        response.setErrorCode(RagErrorCode.RETRIEVAL_FAILED);
        response.setErrorMessage(retrieval.failureReason());
        response.setRetrievedCount(0);
        response.setTiming(timing(retrievalMs, 0L, 0L, 0L, totalMs));
        log.warn("RAG 受控返回：检索失败，未执行生成");
        return response;
    }

    private RagAnswerResponse buildNoEvidence(RagRetrievalResult retrieval,
                                              ContextAssemblyResult context,
                                              long retrievalMs, long rerankMs, long assemblyMs,
                                              long totalMs, RerankResult rerank) {
        RagAnswerResponse response = baseResponse();
        response.setAnswer(NO_EVIDENCE_ANSWER);
        response.setRetrievalStatus(retrieval.status());
        response.setGenerationStatus(GenerationStatus.SKIPPED_NO_EVIDENCE);
        response.setErrorCode(RagErrorCode.NO_EVIDENCE);
        response.setErrorMessage("无可用证据，已按约束跳过 ChatModel 调用");
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setEvidenceCount(0);
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, rerankMs, assemblyMs, 0L, totalMs));
        applyRerankObservability(response, rerank);
        log.warn("RAG 受控返回：无可用证据，未调用 ChatModel");
        return response;
    }

    /**
     * 澄清/拒答/冲突受控决策：不调用生成，回填评估字段。
     */
    private RagAnswerResponse buildControlledDecision(EvidenceAssessmentResult assessment,
                                                      RagRetrievalResult retrieval,
                                                      ContextAssemblyResult context,
                                                      long retrievalMs, long rerankMs,
                                                      long assemblyMs, long totalMs,
                                                      RerankResult rerank) {
        RagAnswerResponse response = baseResponse();
        response.setRetrievalStatus(retrieval.status());
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setEvidenceCount(context.getEvidence().size());
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, rerankMs, assemblyMs, 0L, totalMs));
        applyRerankObservability(response, rerank);
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

    private RagAnswerResponse buildResponse(
            RagRetrievalResult retrieval,
            ContextAssemblyResult context,
            EvidenceAssessmentResult assessment,
            GenerationResult generation,
            CitationVerificationService.CitationVerification verification,
            long retrievalMs, long rerankMs, long assemblyMs,
            long generationMs, long totalMs, RerankResult rerank) {
        RagAnswerResponse response = baseResponse();
        response.setRetrievalStatus(retrieval.status());
        response.setRetrievedCount(retrieval.retrievedCount());
        response.setBudget(context.getBudget());
        response.setTiming(timing(retrievalMs, rerankMs, assemblyMs, generationMs, totalMs));
        applyRerankObservability(response, rerank);
        applyAssessment(response, assessment);

        if (generation.status() != GenerationStatus.SUCCESS) {
            response.setGenerationStatus(generation.status());
            response.setErrorCode(generation.errorCode());
            response.setErrorMessage(generation.errorMessage());
            response.setSources(toSourceViews(context.getEvidence()));
            response.setEvidenceCount(context.getEvidence().size());
            log.warn("RAG 受控返回：生成失败 status={} code={}",
                    generation.status(), generation.errorCode());
            return response;
        }

        List<EvidenceItem> validItems = verification.validItems();

        // 全部来源在响应前被删除/失效：抑制模型答案，受控失败
        if (validItems.isEmpty()) {
            response.setAnswer(null);
            response.setGenerationStatus(GenerationStatus.FAILED_ERROR);
            response.setErrorCode(selectRemovalCode(verification));
            response.setErrorMessage("引用来源在返回前已全部删除或失效，已停止返回该回答。");
            response.setSources(List.of());
            response.setEvidenceCount(0);
            response.setCitationNotes(buildCitationNotes(verification, List.of()));
            log.warn("RAG 受控返回：响应前来源全部失效 code={}", response.getErrorCode());
            return response;
        }

        // cited 仅按回答中真实出现的标记回填，且只标记已核验有效的来源
        citationVerificationService.markCited(generation.content(), validItems);

        // 生成后校验（引用合法性 + 事实支持 + 虚构 URL），以已核验有效来源为准
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
            log.warn("RAG 受控返回：生成后校验未通过 invalid={} unsupported={} fabricatedUrl={}",
                    validation.invalidCitations(), validation.unsupportedClaims(),
                    validation.fabricatedUrls());
            return response;
        }

        response.setAnswer(generation.content());
        response.setGenerationStatus(GenerationStatus.SUCCESS);
        response.setErrorCode(RagErrorCode.NONE);
        response.setUnknownCitationDetected(generation.unknownCitationDetected());
        log.info("RAG 流程结束: evidence={}, valid={}, generation=SUCCESS",
                context.getEvidence().size(), validItems.size());
        return response;
    }

    /**
     * 全部来源被剔除时的对外错误码：删除优先，其次归属不一致，最后是非生效。
     * 三者都是“无法证明引用可返回”，但原因必须可区分。
     */
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
            List<com.zl.d44.rag.assessment.ConflictGroup> groups) {
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
            // 稳定来源标识映射真实 documentId；请求级引用标识对应 sourceRef
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
            // 当前无授权外链来源，url 恒 null（经安全策略解析，入参为 null）
            view.setUrl(citationVerificationService.resolveUrl(null));
            views.add(view);
        }
        return views;
    }

    private RagTimingView timing(long retrievalMs, long rerankMs, long assemblyMs,
                                 long generationMs, long totalMs) {
        RagTimingView view = new RagTimingView();
        view.setRetrievalMs(retrievalMs);
        view.setRerankMs(rerankMs);
        view.setAssemblyMs(assemblyMs);
        view.setGenerationMs(generationMs);
        view.setTotalMs(totalMs);
        return view;
    }

    /**
     * 精排步执行（P2/D1）：端口缺席返回 null（零差异直通）；否则把已过滤命中
     * 映射为类型化候选并调用端口。Port 契约承诺不抛中断异常，此处仍做防御兜底
     * ——任何 RuntimeException 都收敛为 fail-open 原序结果，检索状态语义零触碰（H8）。
     */
    private RerankResult applyRerank(String question, List<SimilarityMatchView> matches) {
        RerankPort port = rerankPortProvider.getIfAvailable();
        if (port == null) {
            return null;
        }
        List<RerankCandidate> candidates = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            SimilarityMatchView m = matches.get(i);
            candidates.add(new RerankCandidate(i, m.getChunkId(), m.getContent()));
        }
        try {
            RerankResult result = port.rerank(question, candidates, matches.size());
            log.info("rerank 步完成: adapter={}, output={}, flags={}, estimatedTokens={}",
                    result.adapter(), result.ordered().size(), result.flags(),
                    result.usage().estimatedTokens());
            // 实验观测行（D-EX6 排序通道）：精排后 uuid 序=rank-sensitive 指标唯一权威输入；
            // 仅在 rerank 装配臂出现，disabled 态零输出（不影响 C05/H1）。
            log.info("rerank order 观测: orderedChunkIds={}",
                    result.ordered().stream()
                            .map(it -> candidates.get(it.candidateOrdinal()).chunkId())
                            .collect(Collectors.joining(",")));
            return result;
        } catch (RuntimeException ex) {
            log.error("rerank 步未预期异常，按 fail-open 回退原序，异常类型: {}",
                    ex.getClass().getSimpleName());
            return RerankResult.identity("defensive", candidates, RerankUsage.zero(0),
                    0L, EnumSet.of(RerankFlags.FAILED_OPEN));
        }
    }

    /**
     * 按精排序重排命中列表（只重排成员不动内容——集合不变性 D2 的执行点）；
     * 缺席/空映射/越界一律回退原序（宁原序不空上下文）。
     */
    private List<SimilarityMatchView> applyRerankOrder(List<SimilarityMatchView> matches,
                                                       RerankResult rerank) {
        if (rerank == null || rerank.ordered().isEmpty() || matches.isEmpty()) {
            return matches;
        }
        List<SimilarityMatchView> ordered = new ArrayList<>(rerank.ordered().size());
        for (RerankResult.Item item : rerank.ordered()) {
            if (item.candidateOrdinal() >= 0 && item.candidateOrdinal() < matches.size()) {
                ordered.add(matches.get(item.candidateOrdinal()));
            }
        }
        return ordered.isEmpty() ? matches : List.copyOf(ordered);
    }

    /**
     * 精排观测回填（P2，additive）：向 BudgetView 的 rerank 字段写入 adapter/calls/
     * tokens/费用/flags——仅装配 rerank 且有预算视图时生效；缺席时字段保持默认，
     * 既有 17 列质量投影与 disabled 态逐字节一致（C05/H1）。
     */
    private void applyRerankObservability(RagAnswerResponse response, RerankResult rerank) {
        if (rerank == null || response.getBudget() == null) {
            return;
        }
        RagBudgetView budget = response.getBudget();
        budget.setRerankAdapter(rerank.adapter());
        budget.setRerankCalls(rerank.usage().calls());
        budget.setRerankMeasuredTokens(rerank.usage().totalTokens());
        budget.setRerankEstimatedTokens(rerank.usage().estimatedTokens());
        budget.setRerankCostCny(rerank.usage().costCny());
        budget.setRerankFlags(rerank.flags().stream().map(Enum::name)
                .sorted().collect(Collectors.joining(",")));
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
