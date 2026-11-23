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
 * D40 P5-C12b 受控漂移构造 harness（KP5 追加裁决授权：单步、最小、先留痕后构造）。
 * 用法: java D40DriftConstruct <out.json> snapshot|construct
 * 环境: MYSQL_PASSWORD / PG_VECTOR_PASSWORD
 * snapshot : 只读留痕——j 的 PG 行、k 的关联行、j/k 版本指针。
 * construct: 留痕后执行三类构造：
 *   (1) PG 删 j 第 1 行（id 已留痕，可经 indexChunks Outbox 快照回放）→ META_ONLY
 *   (2) MySQL 删 k 全部关联行（chunkId 已留痕）→ VECTOR_ONLY
 *   (3) 指针 j→9、k→3（原值已留痕）→ VERSION_MISMATCH(a)
 * 仅触碰 d40-doc-j / d40-doc-k；无 DROP/TRUNCATE；禁止跨前缀。
 */
public final class D40DriftConstruct {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("usage: D40DriftConstruct <out.json> snapshot|construct");
        boolean construct = "construct".equals(args[1]);
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("at", java.time.LocalDateTime.now().toString());
        root.put("mode", args[1]);

        try (Connection pg = DriverManager.getConnection(
                "jdbc:postgresql://192.168.200.153:5432/spring_ai", "postgres", env("PG_VECTOR_PASSWORD"));
             Statement st = pg.createStatement()) {
            ArrayNode jRows = root.putArray("pg_rows_d40-doc-j");
            String firstJId = null;
            try (ResultSet rs = st.executeQuery(
                    "SELECT id::text, metadata->>'version' v FROM d40_vector_store "
                            + "WHERE metadata->>'documentId' = 'd40-doc-j' ORDER BY id")) {
                while (rs.next()) {
                    ObjectNode n = jRows.addObject();
                    n.put("id", rs.getString(1));
                    n.put("version", rs.getString(2));
                    if (firstJId == null) firstJId = rs.getString(1);
                }
            }
            if (construct && firstJId != null) {
                int d = st.executeUpdate("DELETE FROM d40_vector_store WHERE id = '" + firstJId + "'::uuid");
                root.put("pg_deleted_j", d);
                root.put("pg_deleted_j_id", firstJId);
            }
        }
        try (Connection my = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
                "root", env("MYSQL_PASSWORD"));
             Statement st = my.createStatement()) {
            ArrayNode kRows = root.putArray("chunk_rows_d40-doc-k");
            try (ResultSet rs = st.executeQuery(
                    "SELECT chunk_id, version, chunk_content_hash FROM document_chunk_index "
                            + "WHERE document_id = 'd40-doc-k' ORDER BY chunk_id")) {
                while (rs.next()) {
                    ObjectNode n = kRows.addObject();
                    n.put("chunkId", rs.getString(1));
                    n.put("version", rs.getInt(2));
                    n.put("contentHash", rs.getString(3));
                }
            }
            ArrayNode ptr = root.putArray("version_pointers_before");
            try (ResultSet rs = st.executeQuery(
                    "SELECT document_id, version FROM knowledge_document WHERE document_id IN ('d40-doc-j','d40-doc-k')")) {
                while (rs.next()) {
                    ObjectNode n = ptr.addObject();
                    n.put("documentId", rs.getString(1));
                    n.put("version", rs.getInt(2));
                }
            }
            if (construct) {
                int d = st.executeUpdate("DELETE FROM document_chunk_index WHERE document_id = 'd40-doc-k'");
                root.put("mysql_chunk_rows_deleted_k", d);
                int u1 = st.executeUpdate("UPDATE knowledge_document SET version = 9 WHERE document_id = 'd40-doc-j'");
                int u2 = st.executeUpdate("UPDATE knowledge_document SET version = 3 WHERE document_id = 'd40-doc-k'");
                root.put("pointer_set_j9", u1);
                root.put("pointer_set_k3", u2);
            }
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            w.write(om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        System.out.println("[DRIFT-CONSTRUCT] " + args[1] + " -> " + args[0]);
    }

    private static String env(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("ENV_MISSING:" + k);
        return v;
    }
}
