import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * D40 KP2 只读探测（HARD READ-ONLY：仅 SELECT / information_schema，零写入零 DDL）。
 * 密码仅从环境变量 PG_VECTOR_PASSWORD / MYSQL_PASSWORD 读取，不落文件不回显。
 *
 * 输出 JSON：
 *  - pg.d40_vector_store: count / metadata 列类型 / 键全集 / canonical 指纹重算（与 InvertedIndex.build 同算法）
 *  - pg.one_d40_vector_store: count / NOT_EXIST
 *  - mysql: knowledge_document 行数与 documentId 前缀分布与 version/lifecycle 现状；
 *           document_vector_index / document_chunk_index 行数与前缀分布；
 *           d40_auth_principal / d39_auth_principal / d40_document_sync / d40_admin_audit 存在性
 */
public final class D40Probe {

    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder(16384);
        out.append('{');
        // ---------------- PG ----------------
        String pgUrl = "jdbc:postgresql://192.168.200.153:5432/spring_ai";
        try (Connection pg = DriverManager.getConnection(pgUrl, "postgres", env("PG_VECTOR_PASSWORD"))) {
            out.append("\"pg\":{");
            boolean d40Table = existsPgTable(pg, "d40_vector_store");
            out.append("\"d40_vector_store_exists\":").append(d40Table).append(',');
            if (d40Table) {
                out.append("\"d40_vector_store_count\":")
                        .append(scalar(pg, "SELECT count(*) FROM public.d40_vector_store")).append(',');
                String metaType = "?";
                try (Statement st = pg.createStatement();
                     ResultSet rs = st.executeQuery("SELECT data_type FROM information_schema.columns "
                             + "WHERE table_schema='public' AND table_name='d40_vector_store' AND column_name='metadata'")) {
                    if (rs.next()) metaType = rs.getString(1);
                }
                out.append("\"metadata_column_type\":\"").append(metaType).append("\",");
                Set<String> keys = new TreeSet<>();
                try (Statement st = pg.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT DISTINCT jsonb_object_keys(metadata::jsonb) AS k FROM public.d40_vector_store")) {
                    while (rs.next()) keys.add(rs.getString("k"));
                }
                out.append("\"metadata_keys\":").append(jsonArray(keys)).append(',');
                // canonical 指纹（与 InvertedIndex.build/D39Probe 同算法）
                List<String> canonicalLines = new ArrayList<>();
                List<String> rows = new ArrayList<>();
                try (Statement st = pg.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT id::text AS id, content, metadata::text AS meta FROM public.d40_vector_store ORDER BY id::text")) {
                    while (rs.next()) {
                        String chunkId = rs.getString("id");
                        String content = rs.getString("content") == null ? "" : rs.getString("content");
                        JsonNode meta = (rs.getString("meta") == null || rs.getString("meta").isBlank())
                                ? M.createObjectNode() : M.readTree(rs.getString("meta"));
                        String contentSha = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
                        canonicalLines.add(String.join("|", chunkId, contentSha,
                                String.valueOf(content.length()),
                                orNull(textOrNull(meta, "documentId")), orNull(intOrNull(meta, "version")),
                                orNull(textOrNull(meta, "strategy")), orNull(intOrNull(meta, "chunkIndex")),
                                orNull(textOrNull(meta, "splitVersion"))));
                        rows.add(chunkId + " " + orNull(textOrNull(meta, "documentId")) + " v"
                                + orNull(intOrNull(meta, "version")) + " "
                                + orNull(textOrNull(meta, "tenantId")) + "/"
                                + orNull(textOrNull(meta, "deptId")) + "/"
                                + orNull(textOrNull(meta, "deviceId")));
                    }
                }
                Collections.sort(canonicalLines);
                out.append("\"collection_fingerprint\":\"")
                        .append(sha256Hex(String.join("\n", canonicalLines).getBytes(StandardCharsets.UTF_8)))
                        .append("\",");
                out.append("\"rows\":").append(jsonArray(rows)).append(',');
            }
            String oneCount = "NOT_EXIST";
            try {
                oneCount = String.valueOf(scalar(pg, "SELECT count(*) FROM public.one_d40_vector_store"));
            } catch (SQLException ignore) {
                // one_ 轨默认关：表不存在属预期，如实记录
            }
            out.append("\"one_d40_vector_store_count\":\"").append(oneCount).append("\"");
            out.append("},");
        }
        // ---------------- MySQL ----------------
        String myUrl = "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        try (Connection my = DriverManager.getConnection(myUrl, "root", env("MYSQL_PASSWORD"))) {
            out.append("\"mysql\":{");
            // knowledge_document：行数 + 明细（documentId/version/status/lifecycle_status/权限三列非空计数）
            List<String> kdRows = new ArrayList<>();
            try (Statement st = my.createStatement();
                 ResultSet rs = st.executeQuery("SELECT document_id, version, status, lifecycle_status, "
                         + "tenant_id, dept_id, device_id FROM knowledge_document ORDER BY document_id")) {
                while (rs.next()) {
                    kdRows.add(rs.getString(1) + " v=" + rs.getInt(2) + " " + rs.getString(3)
                            + " lifecycle=" + rs.getString(4)
                            + " scope=" + nz(rs.getString(5)) + "/" + nz(rs.getString(6)) + "/" + nz(rs.getString(7)));
                }
            } catch (SQLException ex) {
                // 权限三列缺失（骨架未跑 D39 DDL 时）：退回基础列盘点
                try (Statement st = my.createStatement();
                     ResultSet rs = st.executeQuery("SELECT document_id, version, status FROM knowledge_document ORDER BY document_id")) {
                    while (rs.next()) {
                        kdRows.add(rs.getString(1) + " v=" + rs.getInt(2) + " " + rs.getString(3) + " (NO_SCOPE_COLS)");
                    }
                }
            }
            out.append("\"knowledge_document_count\":").append(kdRows.size()).append(',');
            out.append("\"knowledge_document_rows\":").append(jsonArray(kdRows)).append(',');
            out.append("\"document_vector_index\":").append(prefixCounts(my, "document_vector_index")).append(',');
            out.append("\"document_chunk_index\":").append(prefixCounts(my, "document_chunk_index")).append(',');
            out.append("\"table_exists\":{");
            out.append("\"d40_auth_principal\":").append(existsMyTable(my, "d40_auth_principal")).append(',');
            out.append("\"d39_auth_principal\":").append(existsMyTable(my, "d39_auth_principal")).append(',');
            out.append("\"d40_document_sync\":").append(existsMyTable(my, "d40_document_sync")).append(',');
            out.append("\"d40_admin_audit\":").append(existsMyTable(my, "d40_admin_audit")).append(',');
            // D39 遗留标签行：knowledge_document 权限三列非空行数（若列存在）
            try {
                out.append("\"kd_tagged_rows\":").append(scalar(my,
                        "SELECT count(*) FROM knowledge_document WHERE tenant_id IS NOT NULL")).append(',');
            } catch (SQLException ignore) {
                out.append("\"kd_tagged_rows\":\"NO_SCOPE_COLS\",");
            }
            // auth principal 行数（存在才查）
            if (existsMyTable(my, "d40_auth_principal")) {
                out.append("\"d40_auth_principal_count\":").append(scalar(my, "SELECT count(*) FROM d40_auth_principal"));
            } else {
                out.append("\"d40_auth_principal_count\":\"NOT_EXIST\"");
            }
            out.append("}}");
        }
        out.append('}');
        System.out.println(out);
    }

    /** 表按 document_id 前缀分布：DOC-* 遗留 / fixture-* D39 夹具残留(若有) / d40- 本轮夹具 / 其他 */
    private static String prefixCounts(Connection my, String table) throws SQLException {
        Map<String, Long> map = new LinkedHashMap<>();
        long total = scalar(my, "SELECT count(*) FROM " + table);
        try (Statement st = my.createStatement();
             ResultSet rs = st.executeQuery("SELECT document_id FROM " + table)) {
            while (rs.next()) {
                String id = rs.getString(1);
                String bucket = id == null ? "NULL"
                        : id.startsWith("DOC-") ? "DOC-*"
                        : id.startsWith("d40-") ? "d40-*"
                        : id.startsWith("d39-") ? "d39-*"
                        : id.startsWith("fixture") ? "fixture*" : "OTHER";
                map.merge(bucket, 1L, Long::sum);
            }
        }
        StringBuilder sb = new StringBuilder("{\"total\":").append(total);
        for (Map.Entry<String, Long> e : map.entrySet()) {
            sb.append(",\"").append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    private static boolean existsPgTable(Connection c, String t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name=?")) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static boolean existsMyTable(Connection c, String t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='ai_db' AND table_name=?")) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("ENV_MISSING:" + k);
        return v;
    }

    private static String nz(String v) {
        return v == null ? "null" : v;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || !v.isNumber() ? null : v.asInt();
    }

    private static String orNull(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }

    private static String jsonArray(Collection<String> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : items) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : md.digest(bytes)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
