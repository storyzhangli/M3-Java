package com.zl.d44.prompt;

import com.zl.d44.access.AccessDeniedException;
import com.zl.d44.access.AccessPolicyService;
import com.zl.d44.access.AuthContext;
import com.zl.d44.common.Result;
import com.zl.d44.document.sync.service.AdminAuditService;
import com.zl.d44.prompt.PromptTemplateService;
import com.zl.d44.prompt.dto.PromptTemplateSummary;
import com.zl.d44.prompt.dto.PromptTemplateUpsertRequest;
import com.zl.d44.prompt.dto.PromptTemplateView;
import com.zl.d44.prompt.dto.TemplateStatusUpdateRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Prompt 模板管理与角色清单控制器。
 *
 * <p>全部接口沿用统一 {@link Result} 包装与全局异常处理。FIX-002（G2 收口）：
 * 写接口（创建/更新/启停/删除）在 app.access.enabled=true 时执行 ADMIN-only 守卫
 * 并双分支审计（语义与 DocumentController.guardAdmin 同型）；读接口保持现状。
 * ObjectProvider 条件装配保证 disabled 态零行为差异（H7）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;
    private final ObjectProvider<AdminAuditService> auditProvider;

    public PromptTemplateController(PromptTemplateService promptTemplateService,
                                    ObjectProvider<AccessPolicyService> accessPolicyProvider,
                                    ObjectProvider<AdminAuditService> auditProvider) {
        this.promptTemplateService = promptTemplateService;
        this.accessPolicyProvider = accessPolicyProvider;
        this.auditProvider = auditProvider;
    }

    /**
     * FIX-002：ADMIN 校验 + 双分支审计；access 关闭（bean 缺席）时直通零差异。
     */
    private void guardAdmin(String action, String targetTemplateId) {
        AccessPolicyService policy = accessPolicyProvider.getIfAvailable();
        if (policy == null) {
            return;
        }
        AuthContext context = null;
        try {
            context = policy.requireCurrent();
            policy.requireAdmin(context);
        } catch (AccessDeniedException denied) {
            recordAudit(context, action, targetTemplateId,
                    AdminAuditService.RESULT_DENIED, denied.reason().name());
            throw denied;
        }
        recordAudit(context, action, targetTemplateId, AdminAuditService.RESULT_ALLOWED, "");
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

    /**
     * 模板列表，支持按 scene/status 过滤与名称/templateId 关键字模糊查询。
     */
    @GetMapping("/prompts")
    public Result<List<PromptTemplateSummary>> list(
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.success(promptTemplateService.list(scene, status, keyword));
    }

    /**
     * 模板详情（含正文与变量声明）。
     */
    @GetMapping("/prompts/{templateId}")
    public Result<PromptTemplateView> detail(@PathVariable String templateId) {
        return Result.success(promptTemplateService.detail(templateId));
    }

    /**
     * 创建模板。
     */
    @PostMapping("/prompts")
    public Result<PromptTemplateView> create(@Valid @RequestBody PromptTemplateUpsertRequest request) {
        guardAdmin("prompts.create", request.getTemplateId() == null ? "" : request.getTemplateId());
        log.info("收到模板创建请求，templateId={}", request.getTemplateId());
        return Result.success(promptTemplateService.create(request));
    }

    /**
     * 更新模板（版本自增）。
     */
    @PutMapping("/prompts/{templateId}")
    public Result<PromptTemplateView> update(@PathVariable String templateId,
                                             @Valid @RequestBody PromptTemplateUpsertRequest request) {
        guardAdmin("prompts.update", templateId == null ? "" : templateId);
        log.info("收到模板更新请求，templateId={}", templateId);
        return Result.success(promptTemplateService.update(templateId, request));
    }

    /**
     * 启用/停用模板。
     */
    @PatchMapping("/prompts/{templateId}/status")
    public Result<PromptTemplateView> changeStatus(@PathVariable String templateId,
                                                   @Valid @RequestBody TemplateStatusUpdateRequest request) {
        guardAdmin("prompts.status", templateId == null ? "" : templateId);
        return Result.success(promptTemplateService.changeStatus(templateId, request.getStatus()));
    }

    /**
     * 逻辑删除模板。
     */
    @DeleteMapping("/prompts/{templateId}")
    public Result<Map<String, String>> delete(@PathVariable String templateId) {
        guardAdmin("prompts.delete", templateId == null ? "" : templateId);
        promptTemplateService.logicalDelete(templateId);
        return Result.success(Map.of("templateId", templateId, "deleted", "true"));
    }

    /**
     * 当前可对话角色清单（仅启用中的客服/编程两个角色）。
     */
    @GetMapping("/roles")
    public Result<List<PromptTemplateSummary>> roles() {
        return Result.success(promptTemplateService.listEnabledRoles());
    }
}
