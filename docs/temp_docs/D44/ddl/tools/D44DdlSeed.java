import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.regex.Pattern;

/**
 * D44 KP2 DDL 执行器：逐条执行 D44_schema.sql（CREATE TABLE IF NOT EXISTS 幂等），
 * 随后输出五表行数核验。无 principal 种子（R01 access 关闭，不使用凭据表）。
 *
 * 用法: java D44DdlSeed &lt;schemaSqlPath&gt; &lt;jdbcUrl&gt; &lt;user&gt; &lt;pass&gt;
 */
public class D44DdlSeed {

    private static final Pattern SPLIT = Pattern.compile("(?<=;)\\s*(?=CREATE TABLE)", Pattern.DOTALL);

    public static void main(String[] args) throws Exception {
        String sql = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String[] stmts = SPLIT.split(sql.trim());
        int ok = 0;
        try (Connection c = DriverManager.getConnection(args[1], args[2], args[3])) {
            for (String stmt : stmts) {
                String body = stripComments(stmt).trim();
                if (body.isEmpty()) continue;
                if (!body.endsWith(";")) body += ";";
                try (Statement st = c.createStatement()) {
                    st.execute(body);
                    ok++;
                    System.out.println("[DDL-OK] " + firstLine(body));
                }
            }
            System.out.println("[DDL] executed=" + ok + " statements");
            for (String t : new String[]{"d44_document_sync", "d44_admin_audit",
                    "d44_auth_principal", "d44_table_extract", "d44_table_row"}) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    rs.next();
                    System.out.println("[VERIFY] table=" + t + " rows=" + rs.getLong(1));
                }
            }
        }
        System.out.println("[DONE] D44_DDL_SEED_OK");
    }

    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            if (line.trim().startsWith("--")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String firstLine(String body) {
        String l = body.split("\n", 2)[0].trim();
        return l.length() > 70 ? l.substring(0, 70) + "..." : l;
    }
}


