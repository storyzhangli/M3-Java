package com.zl.d39.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D39-R01 C01 单测：令牌哈希已知向量（与 DDL 种子脚本共用同一实现，两侧一致性契约）
 * 与 {@link AuthContext} 不可变性（SSE 快照传递前提，§3.3）。
 */
class AccessTokenHasherAndAuthContextTest {

    @Test
    @DisplayName("SHA-256 已知向量：'abc' → FIPS 标准 64 位小写 hex")
    void sha256KnownVector() {
        assertThat(AccessTokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("空串已知向量 + 幂等 + 长度/小写不变量")
    void emptyAndIdempotent() {
        assertThat(AccessTokenHasher.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        String token = "d39-fixture-token-用户A-中文与符号#%";
        String first = AccessTokenHasher.sha256Hex(token);
        assertThat(first).hasSize(64).isEqualTo(first.toLowerCase()).isEqualTo(AccessTokenHasher.sha256Hex(token));
    }

    @Test
    @DisplayName("AuthContext：deviceScopes 防御性拷贝且对外不可变（篡改源集合不影响上下文）")
    void authContextImmutable() {
        Set<String> source = new java.util.HashSet<>();
        source.add("DEV-A");
        AuthContext context = new AuthContext("userA", "T1", "D1", source, AuthRole.USER);
        source.add("DEV-EVIL");
        assertThat(context.deviceScopes()).containsExactly("DEV-A");
        assertThatThrownBy(() -> context.deviceScopes().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("AuthContext.admin()：只认服务端 AuthRole，USER=false / ADMIN=true")
    void adminRoleCheck() {
        assertThat(new AuthContext("u", "T1", "D1", Set.of(), AuthRole.USER).admin()).isFalse();
        assertThat(new AuthContext("a", "T1", "D-HQ", Set.of(), AuthRole.ADMIN).admin()).isTrue();
    }

    @Test
    @DisplayName("PermissionScope.of(ctx)：与手工构造代数等值（设备集/通配/角色透传）")
    void scopeOfContextEquivalence() {
        AuthContext context = new AuthContext("userA", "T1", "D1", Set.of("DEV-A"), AuthRole.USER);
        PermissionScope fromCtx = PermissionScope.of(context, "*");
        PermissionScope manual = new PermissionScope("T1", "D1", Set.of("DEV-A"), false, "*");
        assertThat(fromCtx).isEqualTo(manual);
    }
}
