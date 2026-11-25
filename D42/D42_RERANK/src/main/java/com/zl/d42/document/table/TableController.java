package com.zl.d42.document.table;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d42.access.AccessDeniedException;
import com.zl.d42.access.AccessPolicyService;
import com.zl.d42.access.AuthContext;
import com.zl.d42.common.Result;
import com.zl.d42.document.mapper.KnowledgeDocumentMapper;
import com.zl.d42.document.po.KnowledgeDocumentPO;
import com.zl.d42.document.service.DocumentPageService;
import com.zl.d42.document.sync.service.AdminAuditService;
import com.zl.d42.document.table.dto.TableView;
import com.zl.d42.document.table.po.TableExtractPO;
import com.zl.d42.document.table.service.TableRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 表格结构化记录 API（新暴露面，分级=KP1 端点盘点表冻结）：
 * 3 读面走文档级权限校验（{@code AccessPolicyService.requireDocument}，D39 三键代数，
 * 拒绝经既有 advice 折叠 4030），1 写面（重抽取）ADMIN-only + 审计留痕。
 *
 * <p>门禁与审计走 ObjectProvider 双可选依赖：{@code app.access.enabled=false}（默认）
 * 时整体跳过——开发态零差异零写（H1/H2，与 {@code D42SyncController} 同型）。</p>
 *
 * <p>响应契约沿用：HTTP 恒 200 + Result 体码（200/4001/4030/5000）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
public class TableController {

    private final TableRecordService tableService;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentPageService pageService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;
    private final ObjectProvider<AdminAuditService> auditProvider;

    public TableController(TableRecordService tableService,
                           KnowledgeDocumentMapper documentMapper,
                           DocumentPageService pageService,
                           ObjectMapper objectMapper,
                           ObjectProvider<AccessPolicyService> accessPolicyProvider,
                           ObjectProvider<AdminAuditService> auditProvider) {
        this.tableService = tableService;
        this.documentMapper = documentMapper;
        this.pageService = pageService;
        this.objectMapper = objectMapper;
        this.accessPolicyProvider = accessPolicyProvider;
        this.auditProvider = auditProvider;
    }

    /** 读面文档级校验（disabled=跳过）；拒绝由 advice 折叠 4030，无需审计（读面）。 */
    private void guardRead(String documentId) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy == null) {
            return;
        }
        AuthContext context = policy.requireCurrent();
        policy.requireDocument(context, documentId);
    }

    /** ADMIN 校验 + 双分支审计（DENIED 也留痕；写面唯一）。 */
    private void guardAdmin(String action, String targetDocumentId, int version) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy == null) {
            return;
        }
        AuthContext context = null;
        try {
            context = policy.requireCurrent();
            policy.requireAdmin(context);
        } catch (AccessDeniedException denied) {
            recordAudit(context, action, targetDocumentId, version,
                    AdminAuditService.RESULT_DENIED, denied.reason().name());
            throw denied;
        }
        recordAudit(context, action, targetDocumentId, version,
                AdminAuditService.RESULT_ALLOWED, "");
    }

    private void recordAudit(AuthContext context, String action, String target, int version,
                             String result, String reasonCode) {
        AdminAuditService audit = auditProvider.getIfAvailable();
        if (audit == null) {
            return;
        }
        audit.record(context == null ? "ANONYMOUS" : context.userId(),
                context == null ? "" : context.tenantId(),
                context == null ? "" : context.role().name(),
                action, target, version, result, reasonCode);
    }

    private KnowledgeDocumentPO loadDocument(String documentId) {
        return documentMapper.selectOne(new QueryWrapper<KnowledgeDocumentPO>()
                .eq("document_id", documentId));
    }

    /** 文档表格列表（活动版本；无表格返回空列表，不伪造）。 */
    @GetMapping("/documents/{documentId}/tables")
    public Result<List<TableView>> tables(@PathVariable String documentId) throws IOException {
        guardRead(documentId);
        KnowledgeDocumentPO doc = loadDocument(documentId);
        if (doc == null) {
            return Result.fail(4001, "文档不存在: " + documentId);
        }
        List<TableView> views = new ArrayList<>();
        for (TableExtractPO po : tableService.listTables(documentId, doc.getVersion())) {
            views.add(TableView.from(po, objectMapper));
        }
        return Result.success(views);
    }

    /** 表数据行（先定位归属文档并过文档级权限，再放行内容，防越权读格）。 */
    @GetMapping("/tables/{tableId}/rows")
    public Result<List<TableView.RowView>> rows(@PathVariable Long tableId) {
        TableExtractPO table = tableService.findTable(tableId);
        if (table == null) {
            return Result.fail(4001, "表格记录不存在: " + tableId);
        }
        guardRead(table.getDocumentId());
        List<TableView.RowView> views = new ArrayList<>();
        tableService.listRows(tableId).forEach(po -> views.add(TableView.RowView.from(po)));
        return Result.success(views);
    }

    /** 列名+值精确命中查询（活动版本全表扫描；权限=文档级）。 */
    @GetMapping("/documents/{documentId}/tables/query")
    public Result<List<TableRecordService.QueryHit>> query(
            @PathVariable String documentId,
            @RequestParam("column") String column,
            @RequestParam("value") String value,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit)
            throws IOException {
        guardRead(documentId);
        KnowledgeDocumentPO doc = loadDocument(documentId);
        if (doc == null) {
            return Result.fail(4001, "文档不存在: " + documentId);
        }
        return Result.success(tableService.queryHits(documentId, doc.getVersion(),
                column, value, limit));
    }

    /**
     * 重抽取（ADMIN + 审计；幂等语义见 {@link TableRecordService#reExtract}：
     * source 含 pdf=存储行幂等重建，其余=文本表真实重解析；PDF 坐标路真重解析
     * 需重新上传原件——诚实能力边界）。
     */
    @PostMapping("/documents/{documentId}/tables/extract")
    public Result<TableRecordService.PersistResult> reExtract(@PathVariable String documentId)
            throws IOException {
        KnowledgeDocumentPO doc = loadDocument(documentId);
        int version = doc == null ? 0 : doc.getVersion();
        guardAdmin("table.extract", documentId, version);
        if (doc == null) {
            return Result.fail(4001, "文档不存在: " + documentId);
        }
        boolean pdf = doc.getSource() != null && doc.getSource().toLowerCase().contains("pdf");
        var pages = pageService.listPages(documentId);
        return Result.success(tableService.reExtract(documentId, doc.getVersion(), pages, pdf));
    }
}
