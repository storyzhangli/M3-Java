import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * D40 C19 夹具清理 harness（前缀限定 d40-%，留痕 before/after）。
 * 用法: java D40Cleanup <out.json> [apply]
 * 环境: MYSQL_PASSWORD / PG_VECTOR_PASSWORD
 * 范围：knowledge_document(d40-%) / document_chunk_index(d40-%) / document_vector_index(d40-%)
 *       / d40_document_sync(全表=D40 新建治理表) / d40_admin_audit(全表) / d40_vector_store(metadata documentId LIKE 'd40-%')
 * 零触碰：DOC-* / d32eval / D39 轨 / d40_auth_principal（治理基础设施，非文档夹具，保留并计数）。
 */
public final class D40Cleanup {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("usage: D40Cleanup <out.json> [apply]");
        boolean apply = args.length > 1 && args[1].equals("apply");
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("at", java.time.LocalDateTime.now().toString());
        root.put("mode", apply ? "apply" : "dryRun");
        String myUrl = "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String pgUrl = "jdbc:postgresql://192.168.200.153:5432/spring_ai";
        try (Connection my = DriverManager.getConnection(myUrl, "root", System.getenv("MYSQL_PASSWORD"));
             Connection pg = DriverManager.getConnection(pgUrl, "postgres", System.getenv("PG_VECTOR_PASSWORD"));
             Statement st = my.createStatement();
             Statement pst = pg.createStatement()) {
            ObjectNode before = root.putObject("before");
            counts(st, pst, before);
            if (apply) {
                ObjectNode del = root.putObject("deleted_rows");
                del.put("knowledge_document", st.executeUpdate("DELETE FROM knowledge_document WHERE document_id LIKE 'd40-%'"));
                del.put("document_chunk_index", st.executeUpdate("DELETE FROM document_chunk_index WHERE document_id LIKE 'd40-%'"));
                del.put("document_vector_index", st.executeUpdate("DELETE FROM document_vector_index WHERE document_id LIKE 'd40-%'"));
                del.put("d40_document_sync", st.executeUpdate("DELETE FROM d40_document_sync"));
                del.put("d40_admin_audit", st.executeUpdate("DELETE FROM d40_admin_audit"));
                del.put("pg_d40_fixture_rows", pst.executeUpdate(
                        "DELETE FROM d40_vector_store WHERE (metadata->>'documentId') LIKE 'd40-%'"));
            }
            ObjectNode after = root.putObject("after");
            counts(st, pst, after);
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            w.write(om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        System.out.println("[CLEANUP] mode=" + (apply ? "apply" : "dryRun") + " written -> " + args[0]);
    }

    private static void counts(Statement my, Statement pg, ObjectNode n) throws Exception {
        q(my, n, "kd_d40", "SELECT count(*) FROM knowledge_document WHERE document_id LIKE 'd40-%'");
        q(my, n, "kd_doc_zero_touch", "SELECT count(*) FROM knowledge_document WHERE document_id LIKE 'DOC-%'");
        q(my, n, "chunk_d40", "SELECT count(*) FROM document_chunk_index WHERE document_id LIKE 'd40-%'");
        q(my, n, "chunk_other", "SELECT count(*) FROM document_chunk_index WHERE document_id NOT LIKE 'd40-%'");
        q(my, n, "vecidx_d40", "SELECT count(*) FROM document_vector_index WHERE document_id LIKE 'd40-%'");
        q(my, n, "sync_total", "SELECT count(*) FROM d40_document_sync");
        q(my, n, "audit_total", "SELECT count(*) FROM d40_admin_audit");
        q(my, n, "principal_total", "SELECT count(*) FROM d40_auth_principal");
        q(pg, n, "pg_total", "SELECT count(*) FROM d40_vector_store");
        q(pg, n, "pg_d40_fixture", "SELECT count(*) FROM d40_vector_store WHERE (metadata->>'documentId') LIKE 'd40-%'");
    }

    private static void q(Statement st, ObjectNode n, String key, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); n.put(key, rs.getInt(1)); }
    }
}
