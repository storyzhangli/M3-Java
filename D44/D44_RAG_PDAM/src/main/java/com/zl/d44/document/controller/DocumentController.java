package com.zl.d44.document.controller;

import com.zl.d44.access.AccessDeniedException;
import com.zl.d44.access.AccessPolicyService;
import com.zl.d44.access.AuthContext;
import com.zl.d44.common.Result;
import com.zl.d44.document.chunk.dto.ChunkComparisonView;
import com.zl.d44.document.chunk.dto.ChunkView;
import com.zl.d44.document.chunk.dto.ChunkViewMapper;
import com.zl.d44.document.chunk.model.ChunkResult;
import com.zl.d44.document.chunk.model.ChunkStrategy;
import com.zl.d44.document.chunk.service.DocumentChunkService;
import com.zl.d44.document.dto.DocumentImportView;
import com.zl.d44.document.dto.DocumentPageView;
import com.zl.d44.document.dto.DocumentRegisterRequest;
import com.zl.d44.document.dto.DocumentView;
import com.zl.d44.document.service.DocumentImportService;
import com.zl.d44.document.service.DocumentPageService;
import com.zl.d44.document.service.DocumentRegistrationService;
import com.zl.d44.document.support.DocumentConverter;
import com.zl.d44.document.sync.service.AdminAuditService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识文档入口：登记与文件导入职责分离。
 *
 * <p>HTTP 层统一返回 {@link Result}，业务结果通过 code 区分。</p>
 *
 * <p><b>P3 写面收口</b>（H7）：enabled 时 register/import 两个写端点
 * ADMIN-only + 审计留痕；disabled 时门禁整体跳过（H1）。读端点分级不变。</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentRegistrationService registrationService;
    private final DocumentImportService importService;
    private final DocumentPageService pageService;
    private final DocumentConverter converter;
    private final DocumentChunkService chunkService;
    private final ChunkViewMapper chunkViewMapper;

    /**
     * P3：文档级范围校验可选依赖（DOC_SCOPE_CHECK 分级，C18）。enabled 时预览类端点
     * 先校验 AuthContext 对目标文档的读取权限（无标签/跨租户/越部门设备=4030，不泄露存在性差异，
     * 且校验<b>先于</b>任何页面/Chunk 内容返回，H4）；disabled 时 Bean 缺席 → 零差异（H1）。
     */
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    /** P3：写面 ADMIN-only 审计（可选依赖，disabled 时缺席即零留痕）。 */
    private final ObjectProvider<AdminAuditService> auditProvider;

    public DocumentController(DocumentRegistrationService registrationService,
                              DocumentImportService importService,
                              DocumentPageService pageService,
                              DocumentConverter converter,
                              DocumentChunkService chunkService,
                              ChunkViewMapper chunkViewMapper,
                              ObjectProvider<AccessPolicyService> accessPolicyProvider,
                              ObjectProvider<AdminAuditService> auditProvider) {
        this.registrationService = registrationService;
        this.importService = importService;
        this.pageService = pageService;
        this.converter = converter;
        this.chunkService = chunkService;
        this.chunkViewMapper = chunkViewMapper;
        this.accessPolicyProvider = accessPolicyProvider;
        this.auditProvider = auditProvider;
    }

    /** enabled 时执行文档级读取校验；disabled 跳过。 */
    private void checkReadable(String documentId) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy != null) {
            policy.requireDocument(policy.requireCurrent(), documentId);
        }
    }

    /**
     * P3（H7）：ADMIN 校验 + 双分支审计，语义与 DxxSyncController.guardAdmin 一致。
     */
    private void guardAdmin(String action, String targetDocumentId) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy == null) {
            return;
        }
        AuthContext context = null;
        try {
            context = policy.requireCurrent();
            policy.requireAdmin(context);
        } catch (AccessDeniedException denied) {
            recordAudit(context, action, targetDocumentId,
                    AdminAuditService.RESULT_DENIED, denied.reason().name());
            throw denied;
        }
        recordAudit(context, action, targetDocumentId, AdminAuditService.RESULT_ALLOWED, "");
    }

    private void recordAudit(AuthContext context, String action, String target,
                             String result, String reasonCode) {
        AdminAuditService audit = auditProvider.getIfAvailable();
        if (audit == null) {
            return;
        }
        audit.record(context == null ? "ANONYMOUS" : context.userId(),
                context == null ? "" : context.tenantId(),
                context == null ? "" : context.role().name(),
                action, target, 0, result, reasonCode);
    }

    /** 文档登记：仅元数据，落 PENDING。ADMIN-only（写面收口）。 */
    @PostMapping("/register")
    public Result<DocumentView> register(@Valid @RequestBody DocumentRegisterRequest request) {
        guardAdmin("documents.register", request.getDocumentId() == null ? "" : request.getDocumentId());
        return Result.success(converter.toView(registrationService.register(request)));
    }

    /** 文件导入：服务端校验、去重、状态机与基础解析。ADMIN-only（写面收口）。 */
    @PostMapping("/import")
    public Result<DocumentImportView> importFile(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "documentId", required = false) String documentId,
                                                 @RequestParam(value = "source", required = false) String source,
                                                 @RequestParam(value = "title", required = false) String title) {
        guardAdmin("documents.import", documentId == null ? "" : documentId);
        return Result.success(importService.importFile(file, documentId, source, title));
    }

    @GetMapping("/{documentId}")
    public Result<DocumentView> get(@PathVariable("documentId") String documentId) {
        return Result.success(converter.toView(registrationService.getByDocumentId(documentId)));
    }

    @GetMapping
    public Result<List<DocumentView>> list() {
        List<DocumentView> views = registrationService.listAll().stream()
                .map(converter::toView).toList();
        return Result.success(views);
    }

    /** 来源定位：按物理页码顺序查询页面级提取文本。 */
    @GetMapping("/{documentId}/pages")
    public Result<List<DocumentPageView>> pages(@PathVariable("documentId") String documentId) {
        checkReadable(documentId);
        List<DocumentPageView> views = pageService.listPages(documentId).stream()
                .map(converter::toPageView).toList();
        return Result.success(views);
    }

    /** 来源定位：查询单个物理页。 */
    @GetMapping("/{documentId}/pages/{pageNumber}")
    public Result<DocumentPageView> page(@PathVariable("documentId") String documentId,
                                         @PathVariable("pageNumber") int pageNumber) {
        checkReadable(documentId);
        return Result.success(converter.toPageView(pageService.getPage(documentId, pageNumber)));
    }

    /**
     * Chunk 切分：基于已持久化的页面结果，按指定策略真实切分（默认固定长度）。
     * 不触发 Embedding/向量检索，仅返回 Chunk 与来源元数据。
     */
    @GetMapping("/{documentId}/chunks")
    public Result<List<ChunkView>> chunks(
            @PathVariable("documentId") String documentId,
            @RequestParam(value = "strategy", defaultValue = "FIXED_SIZE") ChunkStrategy strategy) {
        checkReadable(documentId);
        ChunkResult result = chunkService.process(pageService.listPages(documentId));
        List<ChunkView> views = chunkViewMapper.toChunkViews(
                strategy == ChunkStrategy.STRUCTURE
                        ? result.getStructureChunks()
                        : result.getFixedChunks());
        return Result.success(views);
    }

    /** 两策略切分对比：相同清洗输入，返回数量/长度/Overlap/覆盖/定位等统计。 */
    @GetMapping("/{documentId}/chunks/comparison")
    public Result<ChunkComparisonView> chunkComparison(@PathVariable("documentId") String documentId) {
        checkReadable(documentId);
        ChunkResult result = chunkService.process(pageService.listPages(documentId));
        return Result.success(chunkViewMapper.toComparisonView(result.getComparison()));
    }
}
