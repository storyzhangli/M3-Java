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
 * D40 C15 审计留痕实查 harness（只读）。
 * 用法: java D40AuditDump <out.json>
 * 环境: MYSQL_PASSWORD
 * 导出 d40_admin_audit 全行 + d40_auth_principal 计数。
 */
public final class D40AuditDump {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("usage: D40AuditDump <out.json>");
        ObjectMapper om = new ObjectMapper();
        ObjectNode root = om.createObjectNode();
        root.put("at", java.time.LocalDateTime.now().toString());
        try (Connection my = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
                "root", System.getenv("MYSQL_PASSWORD"));
             Statement st = my.createStatement()) {
            ArrayNode rows = root.putArray("d40_admin_audit");
            try (ResultSet rs = st.executeQuery(
                    "SELECT actor_user_id, tenant_id, role, action, target_document_id, version, result, reason_code, request_id, created_at "
                            + "FROM d40_admin_audit ORDER BY id")) {
                int n = 0;
                while (rs.next()) {
                    ObjectNode o = rows.addObject();
                    o.put("actorUserId", rs.getString(1));
                    o.put("tenantId", rs.getString(2));
                    o.put("role", rs.getString(3));
                    o.put("action", rs.getString(4));
                    o.put("targetDocumentId", rs.getString(5));
                    o.put("version", rs.getInt(6));
                    o.put("result", rs.getString(7));
                    o.put("reasonCode", rs.getString(8));
                    o.put("requestId", rs.getString(9));
                    o.put("createdAt", String.valueOf(rs.getTimestamp(10)));
                    n++;
                }
                root.put("audit_rows", n);
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM d40_auth_principal")) {
                rs.next();
                root.put("principal_rows", rs.getInt(1));
            }
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            w.write(om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        System.out.println("[AUDIT-DUMP] written -> " + args[0]);
    }
}
