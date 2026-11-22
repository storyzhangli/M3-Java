package com.zl.d39.hybrid.keyword;

import com.zl.d39.access.PermissionScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D39-R01 C01 单测：{@link KeywordFilterCriteria} 权限组件扩展。
 *
 * <p>H1 零差异口径：四参兼容构造 permission=null 时，行为必须与 D38 完全一致；
 * 五参携带 {@link PermissionScope} 时权限判定逐 chunk 生效（先过滤后排名，H4）。</p>
 */
class KeywordFilterCriteriaTest {

    private static final PermissionScope USER =
            new PermissionScope("T1", "D1", Set.of("DEV-A"), false, "*");
    private static final PermissionScope ADMIN =
            new PermissionScope("T1", "D-HQ", Set.of(), true, "*");

    private static KeywordFilterCriteria.ChunkFields chunk(String documentId, String title,
                                                           Integer version, String strategy,
                                                           String tenant, String dept, String device) {
        return new KeywordFilterCriteria.ChunkFields(documentId, title, version, strategy,
                tenant, dept, device);
    }

    @Test
    @DisplayName("兼容四参构造：权限缺省 null → isEmpty/matches 与 D38 语义一致（H1）")
    void legacyFourArgConstructorKeepsD38Behavior() {
        KeywordFilterCriteria legacy = new KeywordFilterCriteria(null, null, null, null);
        assertThat(legacy.permission()).isNull();
        assertThat(legacy.isEmpty()).isTrue();
        assertThat(legacy.matches(chunk("doc-1", "t", 1, "STRUCTURE", null, null, null))).isTrue();

        KeywordFilterCriteria byDoc = new KeywordFilterCriteria("doc-1", null, null, null);
        assertThat(byDoc.isEmpty()).isFalse();
        assertThat(byDoc.matches(chunk("doc-1", "t", 1, "STRUCTURE", null, null, null))).isTrue();
        assertThat(byDoc.matches(chunk("doc-2", "t", 1, "STRUCTURE", null, null, null))).isFalse();

        // 业务四条件组合（无权限）：权限字段不参与判定
        KeywordFilterCriteria business = new KeywordFilterCriteria("doc-1", "标题", 2, "FIXED_SIZE");
        assertThat(business.matches(chunk("doc-1", "标题", 2, "FIXED_SIZE", null, null, null))).isTrue();
        assertThat(business.matches(chunk("doc-1", "标题", 3, "FIXED_SIZE", "T1", "D1", "DEV-A"))).isFalse();
    }

    @Test
    @DisplayName("五参权限构造：业务全空但 permission 非空 → isEmpty=false（过滤态可见）")
    void permissionMakesCriteriaNonEmpty() {
        KeywordFilterCriteria criteria = new KeywordFilterCriteria(null, null, null, null, USER);
        assertThat(criteria.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("USER 权限过滤：跨租户/跨部门/跨设备/无标签剔除，'*' 通配与本体可见（H4/H7）")
    void userPermissionFiltering() {
        KeywordFilterCriteria criteria = new KeywordFilterCriteria(null, null, null, null, USER);
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", "T1", "D1", "DEV-A"))).isTrue();
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", "T1", "*", "*"))).isTrue();
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", "T1", "D2", "DEV-A"))).isFalse();
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", "T1", "D1", "DEV-B"))).isFalse();
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", "T2", "D1", "DEV-A"))).isFalse();
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", null, "D1", "DEV-A"))).isFalse();
    }

    @Test
    @DisplayName("ADMIN 权限过滤：本租户全量（dept/device bypass），跨租户仍拒（H7）")
    void adminPermissionFiltering() {
        KeywordFilterCriteria criteria = new KeywordFilterCriteria(null, null, null, null, ADMIN);
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", "T1", "D2", "DEV-Z"))).isTrue();
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", "T2", "D1", "DEV-A"))).isFalse();
        assertThat(criteria.matches(chunk("d1", "t", 1, "S", null, null, null))).isFalse();
    }

    @Test
    @DisplayName("业务条件 AND 权限组合：业务命中但权限不过 → 拒（先过滤后排名前置条件）")
    void businessAndPermissionConjunction() {
        KeywordFilterCriteria criteria = new KeywordFilterCriteria("doc-1", null, null, null, USER);
        assertThat(criteria.matches(chunk("doc-1", "t", 1, "S", "T1", "D1", "DEV-A"))).isTrue();
        assertThat(criteria.matches(chunk("doc-1", "t", 1, "S", "T2", "D1", "DEV-A"))).isFalse();
        assertThat(criteria.matches(chunk("doc-9", "t", 1, "S", "T1", "D1", "DEV-A"))).isFalse();
    }

    @Test
    @DisplayName("ChunkFields 四参兼容构造：权限三字段 null → USER/ADMIN 皆 fail-closed（D3）")
    void chunkFieldsLegacyFactoryUntagged() {
        KeywordFilterCriteria.ChunkFields untagged =
                new KeywordFilterCriteria.ChunkFields("doc-1", "t", 1, "S");
        assertThat(untagged.tenantId()).isNull();
        assertThat(untagged.deptId()).isNull();
        assertThat(untagged.deviceId()).isNull();
        KeywordFilterCriteria criteria = new KeywordFilterCriteria(null, null, null, null, USER);
        assertThat(criteria.matches(untagged)).isFalse();
    }
}
