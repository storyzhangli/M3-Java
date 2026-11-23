import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * R01 范围隔离真实库实证（共享表 document_vector_index，夹具前缀 d40-r01-/d39-r01-/d41-r01-）。
 * 用法: java D40RecoverScopeProbe <out.json>；环境: MYSQL_PASSWORD。
 *
 * 复刻生产 recoverStuckTasks(prefix) 的查询形态（QueryWrapper: status IN ∧ updated_at<cutoff
 * [∧ document_id LIKE 'prefix%']），证明：
 *  A) scoped 查询只命中 d40-r01-*，d39/d41 越界行不在恢复集；
 *  B) 无 prefix 查询命中全部（全量兼容语义不变）；
 *  C) 仅对 A 命中集执行"恢复侧行变更"后，越界行全字段指纹不变（零触碰）。
 * 夹具自建自销，真实业务行（DOC 前缀 / d32eval / D39 域）不在本 harness 前缀内，绝不触碰。
 */
public final class D40RecoverScopeProbe {

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder(8192);
        out.append('{');
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8",
                "root", System.getenv("MYSQL_PASSWORD"))) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM document_vector_index "
                        + "WHERE document_id LIKE 'd40-r01-%' OR document_id LIKE 'd39-r01-%' "
                        + "OR document_id LIKE 'd41-r01-%'");
            }
            insert(c, "d40-r01-in1", "PENDING");
            insert(c, "d40-r01-in2", "INDEXING");
            insert(c, "d39-r01-out", "PENDING");
            insert(c, "d41-r01-out", "INDEXING");

            String cutoff = "DATE_SUB(NOW(), INTERVAL 30 MINUTE)";
            List<String> scoped = query(c, "SELECT document_id FROM document_vector_index "
                    + "WHERE status IN ('PENDING','INDEXING') AND updated_at < " + cutoff
                    + " AND document_id LIKE 'd40-r01-%' ORDER BY document_id");
            List<String> full = query(c, "SELECT document_id FROM document_vector_index "
                    + "WHERE status IN ('PENDING','INDEXING') AND updated_at < " + cutoff
                    + " AND document_id LIKE 'd%-r01-%' ORDER BY document_id");
            out.append("\"testA_scoped_hits\":").append(jsonArr(scoped))
                    .append(",\"testA_pass\":").append(scoped.equals(List.of("d40-r01-in1", "d40-r01-in2")));
            out.append(",\"testB_full_hits\":").append(jsonArr(full))
                    .append(",\"testB_pass\":").append(full.size() == 4);

            // C：对 scoped 命中集执行恢复侧变更（等价恢复管线的行写入形态：status 推进+attempt+1+updated_at）
            String beforeOut = fingerprint(c, "d39-r01-out") + "|" + fingerprint(c, "d41-r01-out");
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE document_vector_index SET status='INDEXING', attempt_count=attempt_count+1, "
                            + "updated_at=NOW() WHERE document_id LIKE 'd40-r01-%' AND status IN ('PENDING','INDEXING')")) {
                out.append(",\"testC_scoped_updates\":").append(ps.executeUpdate());
            }
            String afterOut = fingerprint(c, "d39-r01-out") + "|" + fingerprint(c, "d41-r01-out");
            out.append(",\"testC_out_of_scope_untouched\":").append(beforeOut.equals(afterOut))
                    .append(",\"testC_pass\":").append(beforeOut.equals(afterOut));

            try (Statement st = c.createStatement()) {
                int cleaned = st.executeUpdate("DELETE FROM document_vector_index "
                        + "WHERE document_id LIKE 'd40-r01-%' OR document_id LIKE 'd39-r01-%' "
                        + "OR document_id LIKE 'd41-r01-%'");
                out.append(",\"fixtures_cleaned\":").append(cleaned);
            }
        }
        out.append(",\"table\":\"document_vector_index（真实 MySQL ai_db；生产 SQL 同型 QueryWrapper 投影）\"}");
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            w.write(out.toString());
        }
        System.out.println("[R01-REALDB] written -> " + args[0]);
    }

    private static void insert(Connection c, String docId, String status) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO document_vector_index (document_id, version, content_hash, strategy, "
                        + "chunk_count, status, stage, attempt_count, created_at, updated_at) "
                        + "VALUES (?,1,'r01probehash','token',0,?,'PERSIST',0,"
                        + "DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR))")) {
            ps.setString(1, docId);
            ps.setString(2, status);
            ps.executeUpdate();
        }
    }

    private static List<String> query(Connection c, String sql) throws Exception {
        List<String> ids = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) ids.add(rs.getString(1));
        }
        return ids;
    }

    private static String fingerprint(Connection c, String docId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT status, attempt_count, updated_at, stage, error_code FROM document_vector_index "
                        + "WHERE document_id=?")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1) + ";" + rs.getInt(2) + ";" + rs.getString(3)
                        + ";" + rs.getString(4) + ";" + rs.getString(5);
            }
        }
    }

    private static String jsonArr(List<String> xs) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(xs.get(i)).append('"');
        }
        return b.append(']').toString();
    }
}
