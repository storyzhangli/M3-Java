package com.zl.d43.document.sync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 特权操作审计表 {@code d43_admin_audit} 的持久化对象（H7/KP1 冻结 schema）。
 *
 * <p>只在 {@code app.access.enabled=true} 时写入（disabled 开发态零差异，H1/H2）；
 * DENIED 与 ALLOWED 都留痕；不记录请求体与任何凭据明文（H9）。</p>
 */
@Data
@NoArgsConstructor
@TableName("d43_admin_audit")
public class AdminAuditPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 服务端解析的 principal userId（绝不信请求声明）。 */
    private String actorUserId;

    private String tenantId;

    /** 服务端权威 role 快照（USER/ADMIN）。 */
    private String role;

    /** 端点动作名（如 vector-index.delete、sync.retry）。 */
    private String action;

    /** 目标文档（无目标时空串）。 */
    private String targetDocumentId;

    /** 相关版本（无=0）。 */
    private Integer version;

    /** ALLOWED / DENIED。 */
    private String result;

    /** 拒绝原因码（放行=空串）。 */
    private String reasonCode;

    /** 既有 requestId 链路贯通（logging MDC 同源）。 */
    private String requestId;

    private LocalDateTime createdAt;
}
