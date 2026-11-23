package com.zl.d40.document.sync.service;

import com.zl.d40.document.sync.po.AdminAuditPO;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.zl.d40.document.sync.mapper.AdminAuditMapper;
import com.zl.d40.logging.TraceIdFilter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 特权操作审计写入服务（H7）。
 *
 * <p>随 {@code app.access.enabled=true} 装配；disabled 时 Bean 不存在，
 * 调用方经 ObjectProvider 跳过——开发态零写、零差异（H1/H2）。</p>
 *
 * <p>可靠性取舍（工程裁决，D40-02 文档留痕）：审计写失败记录 error 日志但<b>不回滚也不阻断</b>
 * 已完成/已拒绝的业务动作——审计是旁路留痕面，业务事务边界不为其扩面；
 * C15 断言在审计表可用前提下验证留痕完整性。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.access", name = "enabled", havingValue = "true")
public class AdminAuditService {

    /** 审计结果枚举值（字符串落库）。 */
    public static final String RESULT_ALLOWED = "ALLOWED";
    public static final String RESULT_DENIED = "DENIED";

    private final AdminAuditMapper auditMapper;

    public AdminAuditService(AdminAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /**
     * 记录一次特权操作裁决。参数空值统一归一化为空串/0（NOT NULL 列）。
     *
     * @param actorUserId 服务端解析 principal（可为 "ANONYMOUS"：无凭据即被拒场景）
     * @param tenantId    租户（无凭据=空串）
     * @param role        服务端 role 快照（无凭据=空串）
     * @param action      端点动作名
     * @param targetDocumentId 目标文档（无=空串）
     * @param version     相关版本（无=0）
     * @param result      ALLOWED / DENIED
     * @param reasonCode  拒绝原因码（放行=空串）
     */
    public void record(String actorUserId, String tenantId, String role, String action,
                       String targetDocumentId, int version, String result, String reasonCode) {
        try {
            AdminAuditPO po = new AdminAuditPO();
            po.setActorUserId(nz(actorUserId));
            po.setTenantId(nz(tenantId));
            po.setRole(nz(role));
            po.setAction(nz(action));
            po.setTargetDocumentId(nz(targetDocumentId));
            po.setVersion(Math.max(0, version));
            po.setResult(result);
            po.setReasonCode(nz(reasonCode));
            String requestId = MDC.get(TraceIdFilter.MDC_REQUEST_ID);
            po.setRequestId(nz(requestId));
            po.setCreatedAt(LocalDateTime.now());
            auditMapper.insert(po);
        } catch (Exception ex) {
            // 旁路留痕失败不阻断业务；错误日志不含请求体与凭据（H9）
            log.error("审计写入失败 action={} result={} type={}", action, result,
                    ex.getClass().getSimpleName());
        }
    }

    /** 查询指定动作的审计行（一致性/证据脚本用；只读）。 */
    public List<AdminAuditPO> findByAction(String action, int limit) {
        return auditMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<AdminAuditPO>()
                        .eq("action", action)
                        .orderByDesc("id")
                        .last("LIMIT " + Math.max(1, Math.min(500, limit))));
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
