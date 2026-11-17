package com.zl.d34.rag.real;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 只读：检查 MySQL 旧表结构与现存行内容（不做任何写操作）。
 * 仅当 D34_TABLE_INSPECT=true 时启用。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "D34_TABLE_INSPECT", matches = "true")
class MySqlTableInspectionTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void inspectOldTables() {
        for (String table : List.of("knowledge_document", "knowledge_document_page")) {
            System.out.println("===== SHOW CREATE TABLE " + table + " =====");
            List<Map<String, Object>> ddl = jdbcTemplate.queryForList("SHOW CREATE TABLE `" + table + "`");
            for (Map<String, Object> row : ddl) {
                for (Object value : row.values()) {
                    System.out.println(value);
                }
            }
            System.out.println("===== ROWS of " + table + " =====");
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM `" + table + "`");
            int index = 0;
            for (Map<String, Object> row : rows) {
                System.out.println("-- row " + (index++));
                row.forEach((k, v) -> {
                    String text = v == null ? "NULL" : String.valueOf(v);
                    if (text.length() > 300) {
                        text = text.substring(0, 300) + "...(truncated)";
                    }
                    System.out.println("  " + k + " = " + text);
                });
            }
        }
    }
}
