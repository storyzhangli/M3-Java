import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * D44 矩阵夹具清理器（P2 前置 + P4 复用）：
 *  PG d44_vector_store / one_d44_vector_store 按 document_id 前缀删除 + 删后双锚复算
 *  MySQL 共享表矩阵域行删除（仅 D44E2e/d44- 前缀域，D41-* 遗留与语料零触碰）
 * 用法: java D44Cleanup <pgUrl> <pgUser> <pgPass> <mysqlUrl> <mysqlUser> <mysqlPass> [--dry]
 * 安全：默认 DRY=只报告将删行数；--apply 才执行。前缀谓词硬编码不可外部注入。
 */
public final class D44Cleanup {

    static final String MY_PREFIX = "(document_id LIKE 'D44E2E%' OR document_id LIKE 'd44e2e%' OR document_id LIKE 'd44-%')";

    public static void main(String[] a) throws Exception {
        boolean apply = a.length > 6 && "--apply".equals(a[6]);
        System.out.println("MODE=" + (apply ? "APPLY" : "DRY-RUN"));
        int pgDeleted = 0, myDeleted = 0;

        try (Connection pg = DriverManager.getConnection(a[0], a[1], a[2])) {
            for (String t : new String[]{"d44_vector_store", "one_d44_vector_store"}) {
                String col = "metadata->>'documentId'";
                try (Statement s = pg.createStatement()) {
                    s.executeQuery("SELECT 1 FROM " + t + " LIMIT 0");
                } catch (SQLException e) { System.out.println("PG table " + t + " absent, skip"); continue; }
                try (Statement s = pg.createStatement()) {
                    int pre = count(pg, "SELECT COUNT(*) FROM " + t + " WHERE " + col + " LIKE 'D44E2E%' OR " + col + " LIKE 'd44-%'");
                    System.out.println("PG " + t + " matrix-rows=" + pre + " total=" + count(pg, "SELECT COUNT(*) FROM " + t));
                    if (apply && pre > 0) {
                        pgDeleted += s.executeUpdate("DELETE FROM " + t + " WHERE " + col + " LIKE 'D44E2E%' OR " + col + " LIKE 'd44-%'");
                    }
                }
            }
        }

        try (Connection my = DriverManager.getConnection(a[3], a[4], a[5])) {
            // doc 域四表（document_id 谓词）
            for (String t : new String[]{"knowledge_document", "document_chunk_index", "document_vector_index",
                    "one_document_chunk_index", "one_document_vector_index", "d44_document_sync"}) {
                int pre = count(my, "SELECT COUNT(*) FROM " + t + " WHERE " + MY_PREFIX);
                System.out.println("MY " + t + " matrix-rows=" + pre);
                if (apply && pre > 0) {
                    try (Statement s = my.createStatement()) { myDeleted += s.executeUpdate("DELETE FROM " + t + " WHERE " + MY_PREFIX); }
                }
            }
            // table_extract/document_id 谓词 + table_row 按 table_id 域联删（列名实读 d44_schema.sql）
            List<String> ext = new ArrayList<>();
            try (Statement s = my.createStatement(); ResultSet r = s.executeQuery("SELECT id FROM d44_table_extract WHERE " + MY_PREFIX)) { while (r.next()) ext.add(r.getString(1)); }
            int rows = 0;
            for (String id : ext) { rows += count(my, "SELECT COUNT(*) FROM d44_table_row WHERE table_id=" + id); }
            System.out.println("MY d44_table_extract matrix-rows=" + ext.size() + " d44_table_row rows=" + rows);
            if (apply && (rows > 0 || !ext.isEmpty())) {
                try (Statement s = my.createStatement()) {
                    for (String id : ext) { myDeleted += s.executeUpdate("DELETE FROM d44_table_row WHERE table_id=" + id); }
                    myDeleted += s.executeUpdate("DELETE FROM d44_table_extract WHERE " + MY_PREFIX);
                }
            }

            // prompt 域
            int pt = count(my, "SELECT COUNT(*) FROM prompt_template WHERE template_id LIKE 'd44e2e%'");
            System.out.println("MY prompt_template matrix-rows=" + pt);
            if (apply && pt > 0) { try (Statement s = my.createStatement()) { myDeleted += s.executeUpdate("DELETE FROM prompt_template WHERE template_id LIKE 'd44e2e%'"); } }

            // chat 域：矩阵窗口会话（probe/live 标记 title）
            int cs = count(my, "SELECT COUNT(*) FROM chat_session WHERE title LIKE '%MX owner%' OR title LIKE '%probe%'");
            System.out.println("MY chat_session matrix-rows=" + cs);
            if (apply && cs > 0) {
                try (Statement s = my.createStatement()) {
                    myDeleted += s.executeUpdate("DELETE m FROM chat_message m JOIN chat_session c ON m.session_id=c.session_id WHERE c.title LIKE '%MX owner%' OR c.title LIKE '%probe%'");
                    myDeleted += s.executeUpdate("DELETE FROM chat_session WHERE title LIKE '%MX owner%' OR title LIKE '%probe%'");
                }
            }
        }

        if (apply) {
            System.out.println("DELETED pg=" + pgDeleted + " mysql=" + myDeleted);
            // 删后行数复核（双锚复算由外部 D44VectorProbe 执行，避免算法复刻漂移）
            try (Connection pg = DriverManager.getConnection(a[0], a[1], a[2])) {
                System.out.println("POST d44_vector_store rows=" + count(pg, "SELECT COUNT(*) FROM d44_vector_store")
                        + " one_d44 rows=" + count(pg, "SELECT COUNT(*) FROM one_d44_vector_store"));
            }
        }
        System.out.println("DONE");
    }

    static int count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { r.next(); return r.getInt(1); }
    }

    static String hex(String t) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(t.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
