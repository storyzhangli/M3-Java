package com.zl.d41.access;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档权限范围只读仓储（MySQL knowledge_document 三列投影，P2 追加列后生效）。
 *
 * <p>列缺失/查询失败一律抛运行时异常向上 fail-closed（H5：绝不因存储不可读而放行）。
 * 文档不存在返回 empty，由调用方按 D3「权限空间外」处理。</p>
 */
@Repository
@ConditionalOnProperty(prefix = "app.access", name = "enabled", havingValue = "true")
public class DocumentScopeRepository {

    private static final String SELECT_SCOPE =
            "SELECT document_id, tenant_id, dept_id, device_id FROM knowledge_document WHERE document_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public DocumentScopeRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DocumentScope> findScope(String documentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(SELECT_SCOPE, documentId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new DocumentScope(
                (String) row.get("document_id"),
                (String) row.get("tenant_id"),
                (String) row.get("dept_id"),
                (String) row.get("device_id")));
    }
}
