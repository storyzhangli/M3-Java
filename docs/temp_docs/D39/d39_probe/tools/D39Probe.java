import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * D39 KP2 只读探测（HARD READ-ONLY：仅 SELECT / information_schema，零写入零 DDL）。
 * 密码仅从环境变量 PG_VECTOR_PASSWORD / MYSQL_PASSWORD 读取，不落文件不回显。
 *
 * 输出 JSON：
 *  - pg.d39_vector_store: count / ids / metadata 键全集 / canonical 集合指纹重算（与 InvertedIndex.build 同算法）
 *  - pg.one_d39_vector_store: count（表不存在 → NOT_EXIST）
 *  - mysql.knowledge_document: 列清单 / 行数（三权限列应不存在）
 *  - mysql.d39_auth_principal: EXISTS / NOT_EXIST
 */
public final class D39Probe {

    private static final ObjectMapper M = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder(8192);
        out.append('{');
        // ---------------- PG ----------------
        String pgUrl = "jdbc:postgresql://192.168.200.153:5432/spring_ai";
        try (Connection pg = DriverManager.getConnection(pgUrl, "postgres", env("PG_VECTOR_PASSWORD"))) {
            out.append("\"pg\":{");
            // 行数
            long count = scalar(pg, "SELECT count(*) FROM public.d39_vector_store");
            out.append("\"d39_vector_store_count\":").append(count).append(',');
            // metadata 列实际类型（KP2 关键发现）
            String metaType = "?";
            try (Statement st = pg.createStatement();
                 ResultSet rs = st.executeQuery("SELECT data_type FROM information_schema.columns "
                         + "WHERE table_schema='public' AND table_name='d39_vector_store' AND column_name='metadata'")) {
                if (rs.next()) metaType = rs.getString(1);
            }
            out.append("\"metadata_column_type\":\"").append(metaType).append("\",");
            // metadata 键全集（json → 显式转 jsonb）
            Set<String> keys = new TreeSet<>();
            try (Statement st = pg.createStatement();
                 ResultSet rs = st.executeQuery("SELECT DISTINCT jsonb_object_keys(metadata::jsonb) AS k FROM public.d39_vector_store")) {
                while (rs.next()) keys.add(rs.getString("k"));
            }
            out.append("\"metadata_keys\":").append(jsonArray(keys)).append(',');
            // KP3 语义实证：json 列上三种谓词形态对缺失键 tenantId 的行为（预期均排除= fail-closed）
            try (Statement st = pg.createStatement()) {
                out.append("\"kp3_missing_key_evidence\":{");
                out.append("\"arrow_total_if_true\":").append(cnt(st, "SELECT count(*) FROM public.d39_vector_store WHERE (metadata->'tenantId') IS NOT NULL")).append(',');
                out.append("\"text_eq_hits\":").append(cnt(st, "SELECT count(*) FROM public.d39_vector_store WHERE metadata->>'tenantId' = 'T1'")).append(',');
                out.append("\"jsonb_contains_hits\":").append(cnt(st, "SELECT count(*) FROM public.d39_vector_store WHERE metadata::jsonb @> '{\"tenantId\":\"T1\"}'::jsonb")).append(',');
                out.append("\"documentId_eq_hits_control\":").append(cnt(st, "SELECT count(*) FROM public.d39_vector_store WHERE metadata->>'documentId' IS NOT NULL")).append(',');
                out.append("\"or_wildcard_shape_hits\":").append(cnt(st, "SELECT count(*) FROM public.d39_vector_store WHERE metadata->>'tenantId' = 'T1' OR metadata->>'tenantId' = '*'")).append("},");
            }
            // 指纹重算（与 InvertedIndex.build 同算法：SELECT ... ORDER BY id::text）
            List<String> canonicalLines = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            try (Statement st = pg.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id::text AS id, content, metadata::text AS meta FROM public.d39_vector_store ORDER BY id::text")) {
                while (rs.next()) {
                    String chunkId = rs.getString("id");
                    ids.add(chunkId);
                    String content = rs.getString("content") == null ? "" : rs.getString("content");
                    JsonNode meta = (rs.getString("meta") == null || rs.getString("meta").isBlank())
                            ? M.createObjectNode() : M.readTree(rs.getString("meta"));
                    String contentSha = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
                    Integer version = meta.hasNonNull("version") && meta.get("version").isNumber()
                            ? meta.get("version").asInt() : null;
                    Integer chunkIndex = meta.hasNonNull("chunkIndex") && meta.get("chunkIndex").isNumber()
                            ? meta.get("chunkIndex").asInt() : null;
                    canonicalLines.add(String.join("|", chunkId, contentSha,
                            String.valueOf(content.length()),
                            orNull(textOrNull(meta, "documentId")), orNull(version),
                            orNull(textOrNull(meta, "strategy")), orNull(chunkIndex),
                            orNull(textOrNull(meta, "splitVersion"))));
                }
            }
            Collections.sort(canonicalLines);
            String fingerprint = sha256Hex(String.join("\n", canonicalLines).getBytes(StandardCharsets.UTF_8));
            out.append("\"collection_fingerprint\":\"").append(fingerprint).append("\",");
            out.append("\"ids\":").append(jsonArray(ids)).append(',');
            // one_ 表
            String oneCount = "NOT_EXIST";
            try {
                oneCount = String.valueOf(scalar(pg, "SELECT count(*) FROM public.one_d39_vector_store"));
            } catch (SQLException ignore) {
                // 表不存在（one_ 轨未启用未建表）——如实记录
            }
            out.append("\"one_d39_vector_store_count\":\"").append(oneCount).append("\"");
            out.append("},");
        }
        // ---------------- MySQL ----------------
        String myUrl = "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        try (Connection my = DriverManager.getConnection(myUrl, "root", env("MYSQL_PASSWORD"))) {
            out.append("\"mysql\":{");
            List<String> cols = new ArrayList<>();
            try (PreparedStatement ps = my.prepareStatement(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='ai_db' AND table_name='knowledge_document' ORDER BY ordinal_position")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) cols.add(rs.getString(1));
                }
            }
            out.append("\"knowledge_document_columns\":").append(jsonArray(cols)).append(',');
            out.append("\"knowledge_document_count\":").append(scalar(my, "SELECT count(*) FROM knowledge_document")).append(',');
            boolean principal = !cols.isEmpty() && existsTable(my, "d39_auth_principal");
            out.append("\"d39_auth_principal_exists\":").append(principal);
            out.append("}");
        }
        out.append('}');
        System.out.println(out);
    }

    private static long cnt(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static boolean existsTable(Connection c, String t) throws SQLException {
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

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
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
