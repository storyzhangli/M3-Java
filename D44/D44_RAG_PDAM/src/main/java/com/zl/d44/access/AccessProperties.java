package com.zl.d44.access;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 知识权限体系配置（前缀 {@code app.access}）。
 *
 * <p>H2 硬契约：{@code enabled} 默认 false（不设 matchIfMissing）；关闭时 access 全套
 * Bean 不装配、认证过滤器不拦截、检索与既有行为逐字节一致（C05 证明）。
 * 参数与 docs/evidence/D44/probe/access-params.json 冻结值一一对应。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.access")
public class AccessProperties {

    /** 权限体系总开关；false（默认）= 全部 access Bean 不装配。 */
    private boolean enabled = false;

    /** 承载 Bearer 凭据的请求头名称（冻结值 Authorization；身份只信服务端，H3）。 */
    private String tokenHeader = "Authorization";

    /** Bearer 方案前缀（含尾随空格）。 */
    private String bearerPrefix = "Bearer ";

    /** 「不限」通配哨兵（D2 裁决：显式 '*'，禁止用 null 表达不限）。 */
    private String wildcardSentinel = "*";

    /**
     * 免认证公开路径（Ant 风格）。仅承载无知识内容的运行元数据；
     * 默认公开 /error 容器回退。健康检查 /api/v1/ai/health 按 D4 分级 OUT_OF_SCOPE，
     * 但启用权限后同样要求凭据（fail-closed 保守：health 响应含运行模式信息，
     * 学习环境统一要求认证更简，裁决留痕 C18）。
     */
    private List<String> publicPaths = List.of("/error");

    /** 认证失败对外稳定业务码（HTTP 保持 200 + Result.code，继承 Result 包装契约）。 */
    private int denyCode = 4030;

    /** 对外统一拒绝文案（不泄露缺失/无效/过期/禁用等内部原因，防枚举探测）。 */
    private String denyMessage = "认证缺失、无效或已过期，请携带有效 Bearer 凭据重试";
}
