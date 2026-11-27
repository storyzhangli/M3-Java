package com.zl.d44.access;

import com.zl.d44.access.AuthContext;
import com.zl.d44.access.DocumentScope;

import java.util.Set;

/**
 * 权限谓词代数的唯一实现（同一 AccessPolicyService 生成，三处消费点共用，§3.1 防漂移）：
 * <ul>
 *   <li>向量路：{@code Filter.Expression}（本代数在 FilterExpressionBuilder AST 上的投影）；</li>
 *   <li>关键词路：{@code KeywordFilterCriteria} 携带本对象，先过滤后排名逐 chunk 调 {@link #matches}；</li>
 *   <li>文档级校验：预览/嵌入端点用 {@link #matchesDocument}。</li>
 * </ul>
 *
 * <p>代数（冻结于 probe/access-params.json）：<br>
 * USER = tenant eq AND (dept eq OR dept '*') AND (device in ctx.devices OR device '*')；<br>
 * ADMIN = tenant eq（租户内 bypass 部门/设备，H7 不得跨租户）。<br>
 * 遗留无标签（任一键空白）= 权限空间外（D3 fail-closed）。</p>
 */
public record PermissionScope(String tenantId,
                              String deptId,
                              Set<String> deviceScopes,
                              boolean admin,
                              String wildcardSentinel) {

    public PermissionScope {
        deviceScopes = Set.copyOf(deviceScopes);
    }

    public static PermissionScope of(AuthContext context, String wildcardSentinel) {
        return new PermissionScope(context.tenantId(), context.deptId(),
                context.deviceScopes(), context.admin(), wildcardSentinel);
    }

    /** 关键词路单 chunk 判定（chunk 三字段允许 null=缺失键，fail-closed）。 */
    public boolean matches(String chunkTenantId, String chunkDeptId, String chunkDeviceId) {
        // 缺失键/空白=遗留无标签 → 权限空间外（D3；与向量路 json 缺失键 NULL 语义同构）
        if (isBlank(chunkTenantId)) {
            return false;
        }
        if (!chunkTenantId.equals(tenantId)) {
            return false;
        }
        if (admin) {
            return true;
        }
        if (!wildcardSentinel.equals(chunkDeptId) && !chunkDeptIdSafeEquals(chunkDeptId)) {
            return false;
        }
        return wildcardSentinel.equals(chunkDeviceId)
                || (chunkDeviceId != null && deviceScopes.contains(chunkDeviceId));
    }

    /** 文档级校验判定（DocumentScope 与 chunk 三字段同构）。 */
    public boolean matchesDocument(DocumentScope document) {
        return matches(document.tenantId(), document.deptId(), document.deviceId());
    }

    private boolean chunkDeptIdSafeEquals(String chunkDeptId) {
        return chunkDeptId != null && chunkDeptId.equals(deptId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
