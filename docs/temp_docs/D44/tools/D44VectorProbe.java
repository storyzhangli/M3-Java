import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * D44 KP2 只读盘点探针：PG d44_vector_store 行数与规范指纹（与 InvertedIndex L153-218 同算法复现）
 * + MySQL 共享表/遗留行盘点。零写入。
 *
 * 用法: java D44VectorProbe <pgUrl> <pgUser> <pgPass> <pgTable> <mysqlUrl> <mysqlUser> <mysqlPass>
 */
public class D44VectorProbe {

    public static void main(String[] args) throws Exception {
        String pgUrl = args[0], pgUser = args[1], pgPass = args[2], pgTable = args[3];
        String myUrl = args[4], myUser = args[5], myPass = args[6];

        System.out.println("== PG " + pgTable + " ==");
        try (Connection c = DriverManager.getConnection(pgUrl, pgUser, pgPass)) {
            // 表是否存在
            boolean exists;
            try (ResultSet rs = c.getMetaData().getTables(null, "public", pgTable, new String[]{"TABLE"})) {
                exists = rs.next();
            }
            System.out.println("TABLE_EXISTS=" + exists);
            if (!exists) {
                System.out.println("ROWS=0 (ABSENT)");
            } else {
                String sql = "SELECT id::text, content, metadata::text FROM " + pgTable + " ORDER BY id::text";
                List<String> lines = new ArrayList<>();
                int n = 0;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        n++;
                        String chunkId = rs.getString(1);
                        String content = rs.getString(2) == null ? "" : rs.getString(2);
                        String meta = rs.getString(3);
                        Map<String, String> m = parseFlatJson(meta);
                        String contentSha = sha256Hex(content.getBytes(StandardCharsets.UTF_8));
                        lines.add(String.join("|", chunkId, contentSha, String.valueOf(content.length()),
                                orNull(m.get("documentId")), orNull(m.get("version")), orNull(m.get("strategy")),
                                orNull(m.get("chunkIndex")), orNull(m.get("splitVersion"))));
                    }
                }
                System.out.println("ROWS=" + n);
                Collections.sort(lines);
                String fp = sha256Hex(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
                System.out.println("FINGERPRINT=" + fp);
                // KP2 内容面锚：同一 canonical 去首列 chunkId（口径=D42 protocol contentFingerprintNoChunkId）
                List<String> noId = new ArrayList<>();
                for (String l : lines) { int bar = l.indexOf('|'); noId.add(l.substring(bar + 1)); }
                Collections.sort(noId); // 锚原版口径=去首列后重排（D42 c377bb2f 复算实证），不可沿用全行序
                System.out.println("CONTENT_FINGERPRINT_NO_CHUNKID=" + sha256Hex(String.join("\n", noId).getBytes(StandardCharsets.UTF_8)));
                for (String l : lines) {
                    System.out.println("CANON " + (l.length() > 160 ? l.substring(0, 160) + "..." : l));
                }
            }
        }

        System.out.println("== MySQL 共享/遗留盘点 ==");
        try (Connection c = DriverManager.getConnection(myUrl, myUser, myPass)) {
            count(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ai_db' AND table_name LIKE 'd43\\_%' ESCAPE '\\'", "D44_TABLES");
            count(c, "SELECT COUNT(*) FROM knowledge_document", "knowledge_document_total");
            count(c, "SELECT COUNT(*) FROM knowledge_document WHERE document_id LIKE 'DOC-%'", "legacy_DOC_prefix_rows");
            count(c, "SELECT COUNT(DISTINCT document_id) FROM knowledge_document WHERE document_id LIKE 'DOC-%'", "legacy_DOC_distinct");
            count(c, "SELECT COUNT(*) FROM document_chunk_index", "document_chunk_index_total");
            count(c, "SELECT COUNT(*) FROM document_chunk_index WHERE document_id NOT IN (SELECT document_id FROM knowledge_document)", "chunk_orphan_rows");
            count(c, "SELECT COUNT(*) FROM document_vector_index", "document_vector_index_total");
            count(c, "SELECT COUNT(*) FROM d44_document_sync", "d44_document_sync_total");
            count(c, "SELECT COUNT(*) FROM d44_table_extract", "d44_table_extract_total");
        }
    }

    /** 末条为占位防误删：捕获异常输出 N/A。 */
    private static void count(Connection c, String sql, String label) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            System.out.println(label + "=" + rs.getLong(1));
        } catch (SQLException e) {
            System.out.println(label + "=N/A(" + e.getSQLState() + ")");
        }
    }

    private static String orNull(String s) { return s == null ? "null" : s; }

    /** metadata 为 jsonb::text（形如 {"k":v,...}）；仅提取所需标量键，宽松解析。 */
    private static Map<String, String> parseFlatJson(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.isBlank()) return out;
        // 简易扫描："key": "value" 或 "key": number
        int i = 0;
        while (i < json.length()) {
            int ks = json.indexOf('"', i);
            if (ks < 0) break;
            int ke = json.indexOf('"', ks + 1);
            if (ke < 0) break;
            String key = json.substring(ks + 1, ke);
            int colon = json.indexOf(':', ke + 1);
            if (colon < 0) break;
            int v = colon + 1;
            while (v < json.length() && json.charAt(v) == ' ') v++;
            if (v >= json.length()) break;
            String val;
            char ch = json.charAt(v);
            if (ch == '"') {
                int ve = json.indexOf('"', v + 1);
                val = json.substring(v + 1, ve);
                i = ve + 1;
            } else {
                int ve = v;
                while (ve < json.length() && ",} \n\r\t".indexOf(json.charAt(ve)) < 0) ve++;
                val = json.substring(v, ve);
                i = ve;
            }
            if (!"null".equals(val)) out.put(key, val);
        }
        return out;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}




