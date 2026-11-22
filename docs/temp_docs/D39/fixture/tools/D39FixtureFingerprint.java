import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * D39-R01 D5 只读 harness：重算 d39_vector_store 全表 canonical 指纹。
 * 算法与 InvertedIndex.build() / D39Probe 逐字节一致：
 * 行 = chunkId|contentSha|chars|documentId|version|strategy|chunkIndex|splitVersion（不含权限键，H6 依据）
 * 指纹 = SHA-256(lines 字典序 \n join)。零写入、零 DDL。
 */
public final class D39FixtureFingerprint {

    public static void main(String[] args) throws Exception {
        String url = System.getenv("D39_PG_URL") == null
                ? "jdbc:postgresql://192.168.200.153:5432/spring_ai" : System.getenv("D39_PG_URL");
        String pass = System.getenv("PG_VECTOR_PASSWORD");
        if (pass == null || pass.isBlank()) {
            System.out.println("BLOCKED=PG_VECTOR_PASSWORD_MISSING");
            System.exit(2);
        }
        ObjectMapper mapper = new ObjectMapper();
        java.util.TreeSet<String> lines = new java.util.TreeSet<>();
        java.util.List<String> perDocReport = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "postgres", pass);
             Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT id::text, content, metadata::text FROM d39_vector_store ORDER BY id::text");
            while (rs.next()) {
                String chunkId = rs.getString(1);
                String content = rs.getString(2) == null ? "" : rs.getString(2);
                JsonNode meta = mapper.readTree(
                        rs.getString(3) == null ? "{}" : rs.getString(3));
                String contentSha = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
                lines.add(String.join("|", chunkId, contentSha, String.valueOf(content.length()),
                        orNull(meta, "documentId"), orNull(meta, "version"), orNull(meta, "strategy"),
                        orNull(meta, "chunkIndex"), orNull(meta, "splitVersion")));
                perDocReport.add(chunkId + " " + orNull(meta, "documentId") + " "
                        + orNull(meta, "tenantId") + "/" + orNull(meta, "deptId") + "/" + orNull(meta, "deviceId"));
            }
        }
        String fingerprint = sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        System.out.println("ROWS=" + lines.size());
        System.out.println("FINGERPRINT=" + fingerprint);
        long tagged = perDocReport.stream().filter(s -> !s.contains(" null/null/null") && s.contains("T")).count();
        System.out.println("TAGGED_ROWS=" + tagged);
        for (String r : perDocReport) {
            System.out.println("ROW " + r);
        }
    }

    private static String orNull(JsonNode meta, String key) {
        JsonNode v = meta.path(key);
        return v.isMissingNode() || v.isNull() ? "null" : v.asText();
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : md.digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
