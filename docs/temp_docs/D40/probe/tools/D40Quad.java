import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * D40 P5 C07 四元组直查 harness（一次性取证，只读）。
 * 用法: java D40Quad <out.json>
 * 环境: MYSQL_PASSWORD / PG_VECTOR_PASSWORD
 * 查 d40-doc-f（PARTIAL 重跑收敛态）与 d40-doc-f2（一次成功参照）：
 *   PG 实存 id 集、MySQL 关联行 chunkId/contentHash 集、任务行 contentHash/status。
 */
public final class D40Quad {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("usage: D40Quad <out.json>");
        String myPw = env("MYSQL_PASSWORD");
        String pgPw = env("PG_VECTOR_PASSWORD");
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("at", java.time.LocalDateTime.now().toString());

        try (Connection pg = DriverManager.getConnection(
                "jdbc:postgresql://192.168.200.153:5432/spring_ai", "postgres", pgPw);
             Statement st = pg.createStatement()) {
            for (String doc : new String[]{"d40-doc-f", "d40-doc-f2", "d40-doc-d"}) {
                ArrayNode arr = root.putArray("pg_ids_" + doc);
                try (ResultSet rs = st.executeQuery(
                        "SELECT id::text, metadata->>'version' v, metadata->>'chunkContentHash' ch "
                                + "FROM d40_vector_store WHERE metadata->>'documentId' = '" + doc + "' ORDER BY 1")) {
                    while (rs.next()) {
                        ObjectNode n = arr.addObject();
                        n.put("id", rs.getString(1));
                        n.put("version", rs.getString(2));
                        n.put("contentHash", rs.getString(3));
                    }
                }
            }
        }
        try (Connection my = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
                "root", myPw);
             Statement st = my.createStatement()) {
            for (String doc : new String[]{"d40-doc-f", "d40-doc-f2", "d40-doc-d"}) {
                ArrayNode arr = root.putArray("chunk_rows_" + doc);
                try (ResultSet rs = st.executeQuery(
                        "SELECT chunk_id, version, chunk_content_hash FROM document_chunk_index "
                                + "WHERE document_id = '" + doc + "' ORDER BY chunk_id")) {
                    while (rs.next()) {
                        ObjectNode n = arr.addObject();
                        n.put("chunkId", rs.getString(1));
                        n.put("version", rs.getInt(2));
                        n.put("contentHash", rs.getString(3));
                    }
                }
                ArrayNode t = root.putArray("task_rows_" + doc);
                try (ResultSet rs = st.executeQuery(
                        "SELECT version, status, content_hash FROM document_vector_index "
                                + "WHERE document_id = '" + doc + "' ORDER BY version")) {
                    while (rs.next()) {
                        ObjectNode n = t.addObject();
                        n.put("version", rs.getInt(1));
                        n.put("status", rs.getString(2));
                        n.put("contentHash", rs.getString(3));
                    }
                }
            }
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            w.write(om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        System.out.println("[QUAD] written -> " + args[0]);
    }

    private static String env(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("ENV_MISSING:" + k);
        return v;
    }
}
