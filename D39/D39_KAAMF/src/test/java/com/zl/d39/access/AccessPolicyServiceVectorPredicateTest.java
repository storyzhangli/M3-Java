package com.zl.d39.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D39-R01 C01 单测：{@link AccessPolicyService#vectorPredicate} 的 AST 结构冻结。
 *
 * <p>API 依据（javap spring-ai-vector-store 1.1.8 实测）：{@code Filter.Expression} 为
 * record 访问器 type()/left()/right()，ExpressionType 常量为大写 AND/OR/EQ/IN，
 * record equals 支持值对拍。渲染→SQL 行为由 T 矩阵在真实 PG 端到端证明（两层互补）。</p>
 */
class AccessPolicyServiceVectorPredicateTest {

    private static AccessPolicyService service() {
        AccessProperties properties = new AccessProperties();
        properties.setWildcardSentinel("*");
        // DocumentScopeRepository 仅 canReadDocument 使用；谓词构建路径不触达，null 安全。
        return new AccessPolicyService(properties, null);
    }

    private static AuthContext user() {
        return new AuthContext("userA", "T1", "D1", Set.of("DEV-A"), AuthRole.USER);
    }

    private static AuthContext admin() {
        return new AuthContext("adminT1", "T1", "D-HQ", Set.of(), AuthRole.ADMIN);
    }

    /**
     * 递归收集表达式内全部节点类型名。
     * 1.1.8 实测 AST：AND/OR/EQ/IN 为 Filter$Expression；group(...) 为 Filter$Group(content)；
     * 键为 Filter$Key(key)、值为 Filter$Value(value)。
     */
    private static List<String> collect(Filter.Operand operand, List<String> sink) {
        if (operand instanceof Filter.Expression expression) {
            sink.add(expression.type().name());
            collect(expression.left(), sink);
            collect(expression.right(), sink);
        } else if (operand instanceof Filter.Group group) {
            sink.add("GROUP");
            collect(group.content(), sink);
        }
        return sink;
    }

    @Test
    @DisplayName("USER 谓词=顶层 AND 三条件：tenant eq ∧ GROUP(dept or) ∧ GROUP(device in)")
    void userPredicateShape() {
        Filter.Expression expression = service().vectorPredicate(user());
        List<String> names = collect(expression, new ArrayList<>());
        assertThat(names).containsExactly("AND", "AND", "EQ", "GROUP", "OR", "EQ", "EQ",
                "GROUP", "IN");
    }

    @Test
    @DisplayName("ADMIN 谓词=单 EQ(tenantId)：不含 OR/IN（租户内 bypass，H7 不得跨租户）")
    void adminPredicateShape() {
        Filter.Expression expression = service().vectorPredicate(admin());
        assertThat(expression.type().name()).isEqualTo("EQ");
        assertThat(expression.left().toString()).contains(AccessPolicyService.KEY_TENANT);
        assertThat(expression.right().toString()).contains("T1");
    }

    @Test
    @DisplayName("谓词键名冻结：tenantId/deptId/deviceId 常量（与入库三键一致，防拼写漂移）")
    void predicateKeysFrozen() {
        assertThat(AccessPolicyService.KEY_TENANT).isEqualTo("tenantId");
        assertThat(AccessPolicyService.KEY_DEPT).isEqualTo("deptId");
        assertThat(AccessPolicyService.KEY_DEVICE).isEqualTo("deviceId");
        Filter.Expression expression = service().vectorPredicate(user());
        assertThat(expression.toString()).contains("tenantId");
    }

    @Test
    @DisplayName("device IN 值集=自有设备∪'*' 哨兵（通配文档可达性契约）")
    void deviceInValuesContainWildcard() {
        Filter.Expression expression = service().vectorPredicate(user());
        assertThat(expression.toString()).contains("DEV-A").contains("*");
    }

    @Test
    @DisplayName("andWithAccess：业务 null → 纯权限谓词；非 null → 顶层 AND(业务, 权限)（H4 前置）")
    void andWithAccessComposition() {
        AccessPolicyService service = service();
        Filter.Expression onlyAccess = service.andWithAccess(null, user());
        assertThat(onlyAccess).isNotNull();
        assertThat(onlyAccess).isEqualTo(service.vectorPredicate(user())); // record 值对拍

        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression business = builder.eq("documentId", "doc-1").build();
        Filter.Expression combined = service.andWithAccess(business, user());
        assertThat(combined.type().name()).isEqualTo("AND");
        assertThat(combined.left()).isSameAs(business); // 手工组合：业务侧原对象左挂（不重建）
        assertThat(combined.right()).isEqualTo(service.vectorPredicate(user()));
    }
}
