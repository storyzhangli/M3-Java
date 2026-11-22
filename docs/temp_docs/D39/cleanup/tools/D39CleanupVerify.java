import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * D39-R01 C19 清理核验 harness（只读 verify / apply 两态，args[0]=apply 才执行 DELETE 夹具行）。
 * 范围硬约束：仅删除本轮夹具（PG metadata documentId ∈ 6 个 DOC-D39-F*；MySQL document_id ∈ 6 个）。
 * 冻结 8 行、历史迭代行零触碰。指纹算法与 D39FixtureFingerprint 一致。
 */
public final class D39CleanupVerify {

    static final String[] FIXTURE_DOCS = { "DOC-D39-F1", "DOC-D39-F2", "DOC-D39-F3", "DOC-D39-F4", "DOC-D39-F5", "DOC-D39-F6" };

    public static void main(String[] args) throws Exception {
        boolean apply = args.length > 0 && args[0].equals("apply");
        String pgUrl = env("D39_PG_URL", "jdbc:postgresql://192.168.200.153:5432/spring_ai");
        String myUrl = env("D39_MY_URL", "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true");
        String pgPw = env("D39_PG_PW", null);
        String myPw = env("D39_MY_PW", null);
        if (pgPw == null || myPw == null) { System.out.println("BLOCKED=PASSWORD_ENV_MISSING"); System.exit(2); }
        String inList = "'" + String.join("','", FIXTURE_DOCS) + "'";

        try (Connection pg = DriverManager.getConnection(pgUrl, "postgres", pgPw)) {
            if (apply) {
                int del = exec(pg, "DELETE FROM d39_vector_store WHERE metadata->>'documentId' IN (" + inList + ")");
                System.out.println("PG_APPLY_DELETED=" + del);
            }
            System.out.println("FIXTURE_ROWS_PG=" + count(pg,
                "SELECT count(*) FROM d39_vector_store WHERE metadata->>'documentId' IN (" + inList + ")"));
            System.out.println("TOTAL_ROWS_PG=" + count(pg, "SELECT count(*) FROM d39_vector_store"));
            // 指纹复算
            ObjectMapper mapper = new ObjectMapper();
            java.util.TreeSet<String> lines = new java.util.TreeSet<>();
            try (Statement st = pg.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id::text, content, metadata::text FROM d39_vector_store ORDER BY id::text")) {
                while (rs.next()) {
                    String content = rs.getString(2) == null ? "" : rs.getString(2);
                    JsonNode meta = mapper.readTree(rs.getString(3) == null ? "{}" : rs.getString(3));
                    lines.add(String.join("|", rs.getString(1), sha256Hex(content.getBytes(StandardCharsets.UTF_8)),
                            String.valueOf(content.length()), orNull(meta, "documentId"), orNull(meta, "version"),
                            orNull(meta, "strategy"), orNull(meta, "chunkIndex"), orNull(meta, "splitVersion")));
                }
            }
            System.out.println("FINGERPRINT=" + sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        }

        try (Connection my = DriverManager.getConnection(myUrl, "root", myPw)) {
            for (String table : new String[] { "knowledge_document", "document_vector_index", "document_chunk_index" }) {
                String col = findDocumentColumn(my, table);
                if (col == null) { System.out.println("FIXTURE_ROWS_MYSQL_" + table + "=NO_COLUMN(SKIP)"); continue; }
                if (apply) {
                    int del = exec(my, "DELETE FROM " + table + " WHERE " + col + " IN (" + inList + ")");
                    System.out.println("MYSQL_APPLY_DELETED_" + table + "=" + del);
                }
                System.out.println("FIXTURE_ROWS_MYSQL_" + table + "=" + count(my,
                    "SELECT count(*) FROM " + table + " WHERE " + col + " IN (" + inList + ")"));
                System.out.println("TOTAL_ROWS_MYSQL_" + table + "=" + count(my, "SELECT count(*) FROM " + table));
            }
        }
    }

    static String env(String k, String def) { String v = System.getenv(k); return v == null || v.isBlank() ? def : v; }
    static String orNull(JsonNode meta, String key) { JsonNode v = meta.path(key); return v.isMissingNode() || v.isNull() ? "null" : v.asText(); }
    static int exec(Connection c, String sql) throws Exception { try (Statement st = c.createStatement()) { return st.executeUpdate(sql); } }
    static long count(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
    static String findDocumentColumn(Connection c, String table) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='ai_db' AND table_name='" + table + "' AND column_name LIKE '%document%' ORDER BY ordinal_position")) {
            if (rs.next()) return rs.getString(1);
        }
        return null;
    }
    static String sha256Hex(byte[] b) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) hex.append(String.format("%02x", x));
        return hex.toString();
    }
}
