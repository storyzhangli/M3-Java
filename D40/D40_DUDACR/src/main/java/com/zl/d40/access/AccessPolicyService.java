package com.zl.d40.access;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 授权策略服务：由服务端 {@link AuthContext} 构建各消费面统一的权限条件（§3.1 单一代数源）。
 *
 * <p>代数唯一实现={@link PermissionScope}；本服务负责其在向量路
 * {@link Filter.Expression}（SQL/JSON Path 下推，Top-K 之前生效）上的投影
 * 与文档级校验入口。禁止任何消费点自行拼写谓词（防双实现漂移，单测断言代数等价）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.access", name = "enabled", havingValue = "true")
public class AccessPolicyService {

    /** 权限三键 metadata 字段名（与 DocumentVectorIndexService 写入键、ChunkFields 读取键一致）。 */
    public static final String KEY_TENANT = "tenantId";
    public static final String KEY_DEPT = "deptId";
    public static final String KEY_DEVICE = "deviceId";

    private final AccessProperties properties;
    private final DocumentScopeRepository documentScopeRepository;

    public AccessPolicyService(AccessProperties properties,
                               DocumentScopeRepository documentScopeRepository) {
        this.properties = properties;
        this.documentScopeRepository = documentScopeRepository;
    }

    /** 取当前请求授权上下文（无上下文=拒绝，H3/H5；同步链路唯一合法入口）。 */
    public AuthContext requireCurrent() {
        return AuthContextHolder.current();
    }

    /** 当前请求的权限代数（便捷入口：requireCurrent → PermissionScope.of）。 */
    public PermissionScope currentScope() {
        return scopeOf(requireCurrent());
    }

    /** 代数投影：AuthContext → PermissionScope（冻结通配哨兵）。 */
    public PermissionScope scopeOf(AuthContext context) {
        return PermissionScope.of(context, properties.getWildcardSentinel());
    }

    /**
     * 向量路权限谓词：{@link PermissionScope} 在 FilterExpressionBuilder AST 上的等价投影。
     *
     * <p>USER = eq(tenant) AND ( eq(dept) OR eq(dept,'*') ) AND ( in(device, devices∪{'*'}) )；
     * ADMIN = eq(tenant)。设备维度用 IN(自有设备 ∪ 哨兵) 与 or(in,eq) 等价的合取范式
     * （单测对拍两种写法渲染文本一致语义）。缺失键行为（KP3 实证）：json 列上
     * 三形态谓词均 NULL 不成立 → 遗留无标签行天然排除（与 matches 的 isBlank 分支同构）。</p>
     */
    public Filter.Expression vectorPredicate(AuthContext context) {
        PermissionScope scope = scopeOf(context);
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> conditions = new ArrayList<>();
        conditions.add(builder.eq(KEY_TENANT, scope.tenantId()));
        if (!scope.admin()) {
            conditions.add(builder.group(
                    builder.or(builder.eq(KEY_DEPT, scope.deptId()),
                            builder.eq(KEY_DEPT, scope.wildcardSentinel()))));
            List<Object> deviceValues = new ArrayList<>(scope.deviceScopes().size() + 1);
            deviceValues.addAll(scope.deviceScopes());
            deviceValues.add(scope.wildcardSentinel());
            conditions.add(builder.group(builder.in(KEY_DEVICE, deviceValues)));
        }
        FilterExpressionBuilder.Op combined = conditions.get(0);
        for (int i = 1; i < conditions.size(); i++) {
            combined = builder.and(combined, conditions.get(i));
        }
        return combined.build();
    }

    /**
     * 把权限谓词 AND 到既有业务过滤表达式上（任一为 null 时返回另一方；均 null 返回 null）。
     * 使用 {@code Filter.Expression(ExpressionType.AND, left, right)} 手工组合
     * （1.1.8 javap 证实构造公开、Expression implements Operand）。
     */
    public Filter.Expression andWithAccess(Filter.Expression businessExpression, AuthContext context) {
        Filter.Expression access = vectorPredicate(context);
        if (businessExpression == null) {
            return access;
        }
        return new Filter.Expression(Filter.ExpressionType.AND, businessExpression, access);
    }

    /**
     * 文档级范围校验（预览/嵌入/分块等端点，C18）：无标签=不可见（D3）；
     * ADMIN 限本租户（H7）。文档不存在按权限空间外处理（不泄露存在性差异）。
     */
    public boolean canReadDocument(AuthContext context, String documentId) {
        return documentScopeRepository.findScope(documentId)
                .map(scope -> scopeOf(context).matchesDocument(scope))
                .orElse(false);
    }

    /** 文档级校验失败即拒（供 Controller 直接调用，稳定拒绝码经 advice 折叠）。 */
    public void requireDocument(AuthContext context, String documentId) {
        if (!canReadDocument(context, documentId)) {
            throw new AccessDeniedException(AccessDeniedException.Reason.OUT_OF_SCOPE);
        }
    }

    /** admin-only 判定（vector-store raw / rebuild，D4）：只认服务端 role。 */
    public void requireAdmin(AuthContext context) {
        if (!context.admin()) {
            throw new AccessDeniedException(AccessDeniedException.Reason.ADMIN_REQUIRED);
        }
    }
}
