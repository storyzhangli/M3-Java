package com.zl.d34.rag.real;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D34 真实 MySQL 表重建测试（受控、一次性）。
 *
 * <p>仅当环境变量 {@code D34_SCHEMA_REBUILD=true} 时启用，默认离线构建跳过。流程：</p>
 * <ol>
 *   <li>重建前取证：旧表是否存在、行数与现存行；</li>
 *   <li>先备份：把旧表 DDL 与全部行导出为可还原 SQL（目录由环境变量 D34_BACKUP_DIR 指定）；</li>
 *   <li>按顺序 DROP 旧表 knowledge_document_page、knowledge_document；</li>
 *   <li>用当前 classpath schema 脚本重建两张表；</li>
 *   <li>核验列与默认值：lifecycle_status 默认 ACTIVE，page 表关键列齐全。</li>
 * </ol>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "D34_SCHEMA_REBUILD", matches = "true")
class MySqlSchemaRebuildTest {

    private static final String TABLE_DOC = "knowledge_document";
    private static final String TABLE_PAGE = "knowledge_document_page";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void backupThenRebuildKnowledgeTables() throws Exception {
        // 1) 重建前取证
        boolean docExists = tableExists(TABLE_DOC);
        boolean pageExists = tableExists(TABLE_PAGE);
        long docRows = docExists ? countRows(TABLE_DOC) : 0L;
        long pageRows = pageExists ? countRows(TABLE_PAGE) : 0L;
        System.out.println("[schema-rebuild] before: docExists=" + docExists
                + " docRows=" + docRows + " pageExists=" + pageExists + " pageRows=" + pageRows);

        // 2) 先备份（存在数据时必须备份成功才允许 DROP）
        Path backupPath = backupExistingTables(docExists, pageExists);
        System.out.println("[schema-rebuild] backup written: " + backupPath);
        assertThat(Files.size(backupPath)).isPositive();

        // 3) DROP 旧表（page 先、doc 后）
        jdbcTemplate.execute("DROP TABLE IF EXISTS `" + TABLE_PAGE + "`");
        jdbcTemplate.execute("DROP TABLE IF EXISTS `" + TABLE_DOC + "`");
        assertThat(tableExists(TABLE_DOC)).isFalse();
        assertThat(tableExists(TABLE_PAGE)).isFalse();
        System.out.println("[schema-rebuild] old tables dropped");

        // 4) 用当前 schema 脚本重建
        executeScript("db/schema-knowledge-document.sql");
        executeScript("db/schema-document-page.sql");
        assertThat(tableExists(TABLE_DOC)).isTrue();
        assertThat(tableExists(TABLE_PAGE)).isTrue();
        System.out.println("[schema-rebuild] tables recreated from current schema");

        // 5) 核验 knowledge_document.lifecycle_status
        Map<String, Object> lifecycleColumn = findColumn(TABLE_DOC, "lifecycle_status");
        assertThat(lifecycleColumn).isNotNull();
        String columnType = String.valueOf(lifecycleColumn.get("COLUMN_TYPE"));
        String columnDefault = String.valueOf(lifecycleColumn.get("COLUMN_DEFAULT"));
        assertThat(columnType).containsIgnoringCase("varchar");
        assertThat(columnDefault).isEqualTo("ACTIVE");
        System.out.println("[schema-rebuild] lifecycle_status: type=" + columnType
                + " default=" + columnDefault);

        // 6) 核验 knowledge_document_page 关键列
        assertThat(findColumn(TABLE_PAGE, "document_id")).isNotNull();
        assertThat(findColumn(TABLE_PAGE, "page_number")).isNotNull();
        assertThat(findColumn(TABLE_PAGE, "content")).isNotNull();
        assertThat(findColumn(TABLE_PAGE, "blank")).isNotNull();

        // 7) 重建后两表行数应为 0
        assertThat(countRows(TABLE_DOC)).isZero();
        assertThat(countRows(TABLE_PAGE)).isZero();
        System.out.println("[schema-rebuild] verification passed");
    }

    private Path backupExistingTables(boolean docExists, boolean pageExists) throws Exception {
        String backupDir = System.getenv("D34_BACKUP_DIR");
        assertThat(backupDir).as("存在数据时必须通过 D34_BACKUP_DIR 指定备份目录").isNotBlank();
        Path dir = Path.of(backupDir);
        Files.createDirectories(dir);
        Path backup = dir.resolve("mysql-pre-rebuild-backup-01.sql");

        StringBuilder out = new StringBuilder();
        out.append("-- D34 MySQL 表重建前备份（自动生成，可用于还原旧表与数据）\n");
        out.append("-- 生成时间: ").append(java.time.LocalDateTime.now()).append('\n');
        for (String table : List.of(TABLE_DOC, TABLE_PAGE)) {
            if (!tableExists(table)) {
                continue;
            }
            out.append("\n-- ===== 旧表 DDL: ").append(table).append(" =====\n");
            jdbcTemplate.queryForList("SHOW CREATE TABLE `" + table + "`")
                    .forEach(row -> row.values().forEach(v -> {
                        String ddl = String.valueOf(v);
                        if (ddl.trim().startsWith("CREATE TABLE")) {
                            out.append(ddl).append(";\n");
                        }
                    }));

            out.append("-- ===== 数据行: ").append(table).append(" =====\n");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM `" + table + "`");
            for (Map<String, Object> row : rows) {
                List<String> columns = new ArrayList<>(row.keySet());
                out.append("INSERT INTO `").append(table).append("` (");
                out.append(String.join(",", columns.stream().map(c -> "`" + c + "`").toList()));
                out.append(") VALUES (");
                List<String> values = columns.stream().map(c -> formatSqlValue(row.get(c))).toList();
                out.append(String.join(",", values)).append(");\n");
            }
        }
        Files.writeString(backup, out.toString(), StandardCharsets.UTF_8);
        return backup;
    }

    private String formatSqlValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value)
                .replace("\\", "\\\\")
                .replace("'", "''");
        return "'" + text + "'";
    }

    private void executeScript(String classpathLocation) throws Exception {
        String sql = StreamUtils.copyToString(
                new ClassPathResource(classpathLocation).getInputStream(),
                StandardCharsets.UTF_8);
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\r?\\n")) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        for (String statement : cleaned.toString().split(";")) {
            String stmt = statement.trim();
            if (!stmt.isEmpty()) {
                jdbcTemplate.execute(stmt);
            }
        }
    }

    private boolean tableExists(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Long.class, table);
        return count != null && count > 0;
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `" + table + "`", Long.class);
        return count == null ? 0L : count;
    }

    private Map<String, Object> findColumn(String table, String column) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                table, column);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
