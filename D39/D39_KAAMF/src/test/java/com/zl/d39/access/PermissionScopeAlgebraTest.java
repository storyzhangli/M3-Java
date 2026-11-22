package com.zl.d39.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D39-R01 C01 单测：{@link PermissionScope} 权限代数真值表（向量路/关键词路唯一实现源）。
 *
 * <p>覆盖冻结代数（probe/access-params.json）：</p>
 * <ul>
 *   <li>USER = tenantEq AND (deptEq OR dept='*') AND (device∈scopes OR device='*')</li>
 *   <li>ADMIN = tenantEq（租户内 bypass，H7 不得跨租户）</li>
 *   <li>缺失键（tenant null/blank）= 遗留无标签 → fail-closed（D3）</li>
 *   <li>租户维度无通配语义：chunk tenant='*' 不等于任何 ctx.tenant（H7 硬边界）</li>
 * </ul>
 */
class PermissionScopeAlgebraTest {

    private static final String WILDCARD = "*";

    private static PermissionScope userScope() {
        return new PermissionScope("T1", "D1", Set.of("DEV-A"), false, WILDCARD);
    }

    private static PermissionScope adminScope() {
        return new PermissionScope("T1", "D-HQ", Set.of(), true, WILDCARD);
    }

    /** 独立复写的期望代数（与实现解耦的第三份口径，防实现漂移即失效）。 */
    private static boolean expected(String tenant, String dept, String device, boolean admin) {
        boolean tenantHit = tenant != null && !tenant.isBlank() && tenant.equals("T1");
        if (!tenantHit) {
            return false;
        }
        if (admin) {
            return true;
        }
        boolean deptHit = WILDCARD.equals(dept) || (dept != null && dept.equals("D1"));
        boolean deviceHit = WILDCARD.equals(device) || (device != null && Set.of("DEV-A").contains(device));
        return deptHit && deviceHit;
    }

    @Test
    @DisplayName("USER 全维真值表：tenant×dept×device（含 null/blank/通配/越权）逐一对照")
    void userTruthTable() {
        String[] tenants = {"T1", "T2", null, "", "  ", "*"};
        String[] depts = {"D1", "*", "D2", null, ""};
        String[] devices = {"DEV-A", "*", "DEV-B", null, ""};
        PermissionScope scope = userScope();
        for (String tenant : tenants) {
            for (String dept : depts) {
                for (String device : devices) {
                    boolean actual = scope.matches(tenant, dept, device);
                    boolean expect = expected(tenant, dept, device, false);
                    assertThat(actual)
                            .as("USER tenant=%s dept=%s device=%s", tenant, dept, device)
                            .isEqualTo(expect);
                }
            }
        }
    }

    @Test
    @DisplayName("ADMIN 真值表：只看租户；部门/设备任意（含缺失也不影响租户判定）")
    void adminTruthTable() {
        String[] tenants = {"T1", "T2", null, "*", "t1"};
        String[] depts = {"D1", "*", null, "D2"};
        String[] devices = {"DEV-A", "*", null, "DEV-Z"};
        PermissionScope scope = adminScope();
        for (String tenant : tenants) {
            for (String dept : depts) {
                for (String device : devices) {
                    boolean actual = scope.matches(tenant, dept, device);
                    assertThat(actual)
                            .as("ADMIN tenant=%s dept=%s device=%s", tenant, dept, device)
                            .isEqualTo(expected(tenant, dept, device, true));
                }
            }
        }
    }

    @Test
    @DisplayName("H7 租户硬边界：跨租户一律拒（含 ADMIN），租户通配符无效")
    void tenantHardBoundary() {
        assertThat(userScope().matches("T2", "D1", "DEV-A")).isFalse();
        assertThat(adminScope().matches("T2", "D-HQ", "*")).isFalse();
        assertThat(userScope().matches("*", "D1", "DEV-A")).isFalse();
        assertThat(adminScope().matches("*", "*", "*")).isFalse();
    }

    @Test
    @DisplayName("D3 遗留无标签：tenant null/blank/空白 = 权限空间外，任何角色不可见")
    void legacyUntaggedFailClosed() {
        for (String untagged : new String[]{null, "", "   "}) {
            assertThat(userScope().matches(untagged, "D1", "DEV-A")).isFalse();
            assertThat(adminScope().matches(untagged, "*", "*")).isFalse();
        }
    }

    @Test
    @DisplayName("空设备集合 USER：仅 '*' 设备文档可见，专属设备文档不可见")
    void emptyDeviceScopes() {
        PermissionScope noDevices = new PermissionScope("T1", "D1", Set.of(), false, WILDCARD);
        assertThat(noDevices.matches("T1", "D1", "*")).isTrue();
        assertThat(noDevices.matches("T1", "D1", "DEV-A")).isFalse();
    }

    @Test
    @DisplayName("多设备 USER：scopes 内任一设备或 '*' 均可见")
    void multiDeviceScopes() {
        PermissionScope multi = new PermissionScope("T1", "D1", Set.of("DEV-A", "DEV-B"), false, WILDCARD);
        assertThat(multi.matches("T1", "D1", "DEV-B")).isTrue();
        assertThat(multi.matches("T1", "D1", "*")).isTrue();
        assertThat(multi.matches("T1", "D1", "DEV-C")).isFalse();
    }

    @Test
    @DisplayName("matchesDocument 与 chunk 三字段判定同构（文档级校验共用唯一代数）")
    void documentLevelEquivalence() {
        PermissionScope scope = userScope();
        DocumentScope doc = new DocumentScope("d1", "T1", "D1", "DEV-A");
        assertThat(scope.matchesDocument(doc)).isEqualTo(scope.matches("T1", "D1", "DEV-A"));
        assertThat(scope.matchesDocument(new DocumentScope("d2", null, "D1", "*"))).isFalse();
        assertThat(scope.matchesDocument(new DocumentScope("d3", "T1", null, null))).isFalse();
    }
}
