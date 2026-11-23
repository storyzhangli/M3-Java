import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

/**
 * D40 P5 只读快照 harness：把矩阵各步的 PG/MySQL 事实导出为 JSON 证据。
 * 零写入；密码仅环境变量 MYSQL_PASSWORD / PG_VECTOR_PASSWORD。
 * args[0]=输出 JSON 路径；args[1]=阶段标签。
 */
public final class D40P5Snapshot {

    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        String phase = args.length > 1 ? args[1] : "snapshot";
        String myUrl = "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String pgUrl = "jdbc:postgresql://192.168.200.153:5432/spring_ai";
        String myPass = System.getenv("MYSQL_PASSWORD");
        String pgPass = System.getenv("PG_VECTOR_PASSWORD");
        if (myPass == null || pgPass == null) {
            throw new IllegalStateException("ENV_MISSING");
        }
        ObjectNode root = M.createObjectNode();
        root.put("phase", phase);
        root.put("at", System.currentTimeMillis());

        try (Connection c = DriverManager.getConnection(pgUrl, "postgres", pgPass)) {
            ArrayNode perDoc = root.putArray("pg_d40_vector_store_by_doc");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT metadata->>'documentId' AS d, count(*) n
                         FROM d40_vector_store
                         GROUP BY 1 ORDER BY 1""")) {
                while (rs.next()) {
                    ObjectNode o = perDoc.addObject();
                    o.put("documentId", rs.getString(1));
                    o.put("rows", rs.getInt(2));
                }
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM d40_vector_store")) {
                rs.next();
                root.put("pg_total_rows", rs.getInt(1));
            }
        }

        try (Connection c = DriverManager.getConnection(myUrl, "root", myPass)) {
            ArrayNode kd = root.putArray("knowledge_document_d40");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT document_id, version, lifecycle_status
                         FROM knowledge_document WHERE document_id LIKE 'd40-%' ORDER BY document_id""")) {
                while (rs.next()) {
                    ObjectNode o = kd.addObject();
                    o.put("documentId", rs.getString(1));
                    o.put("version", rs.getInt(2));
                    o.put("lifecycle", rs.getString(3));
                }
            }
            ArrayNode sync = root.putArray("d40_document_sync");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT document_id, operation, version, sync_status, reason_code,
                                attempt_count, payload_hash
                         FROM d40_document_sync ORDER BY id""")) {
                while (rs.next()) {
                    ObjectNode o = sync.addObject();
                    o.put("documentId", rs.getString(1));
                    o.put("operation", rs.getString(2));
                    o.put("version", rs.getInt(3));
                    o.put("status", rs.getString(4));
                    o.put("reason", rs.getString(5));
                    o.put("attempt", rs.getInt(6));
                    o.put("payloadHash12", rs.getString(7) == null ? "" : rs.getString(7).substring(0, Math.min(12, rs.getString(7).length())));
                }
            }
            ArrayNode chunks = root.putArray("chunk_index_d40");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT document_id, count(*) n FROM document_chunk_index
                         WHERE document_id LIKE 'd40-%' GROUP BY 1 ORDER BY 1""")) {
                while (rs.next()) {
                    ObjectNode o = chunks.addObject();
                    o.put("documentId", rs.getString(1));
                    o.put("rows", rs.getInt(2));
                }
            }
            ArrayNode tasks = root.putArray("vector_index_tasks_d40");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT document_id, version, status FROM document_vector_index
                         WHERE document_id LIKE 'd40-%' ORDER BY document_id, version""")) {
                while (rs.next()) {
                    ObjectNode o = tasks.addObject();
                    o.put("documentId", rs.getString(1));
                    o.put("version", rs.getInt(2));
                    o.put("status", rs.getString(3));
                }
            }
        }
        Files.createDirectories(out.getParent());
        Files.writeString(out, M.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        System.out.println("[SNAPSHOT] " + phase + " -> " + out + " pg_total=" + root.get("pg_total_rows").asInt());
    }
}
