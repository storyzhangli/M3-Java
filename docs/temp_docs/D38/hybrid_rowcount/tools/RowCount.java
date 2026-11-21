package tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * C06 三轨隔离行数快照（全只读，零 DDL 零写入）。
 * 密码仅经环境变量注入（PG_VECTOR_PASSWORD / MYSQL_PASSWORD），禁止回显。
 * 输出：JSON 单行 {pg:{table:count,...}, mysql:{table:count,...}}
 */
public final class RowCount {

    public static void main(String[] args) throws Exception {
        String pgPwd = System.getenv("PG_VECTOR_PASSWORD");
        String myPwd = System.getenv("MYSQL_PASSWORD");
        if (pgPwd == null || pgPwd.isBlank()) {
            System.out.println("{\"error\":\"PG_VECTOR_PASSWORD_MISSING\"}");
            System.exit(1);
        }
        StringBuilder sb = new StringBuilder("{");
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://192.168.200.153:5432/spring_ai", "postgres", pgPwd);
             Statement st = c.createStatement()) {
            sb.append("\"pg\":{");
            boolean first = true;
            String[] tables = {"d38_vector_store", "d37_vector_store"};
            for (String t : tables) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM public.\"" + t + "\"")) {
                    if (rs.next()) {
                        if (!first) {
                            sb.append(',');
                        }
                        sb.append('"').append(t).append("\":").append(rs.getLong(1));
                        first = false;
                    }
                }
            }
            // one_ 轨表（R01 阿里百炼新表，名称按 pg_tables 实况枚举，防表名记错）
            java.util.List<String> oneTables = new java.util.ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname='public' "
                            + "AND (tablename LIKE 'd38_one%' OR tablename LIKE 'one%') ORDER BY tablename")) {
                while (rs.next()) {
                    oneTables.add(rs.getString(1));
                }
            }
            for (String t : oneTables) {
                try (ResultSet rc = st.executeQuery("SELECT count(*) FROM public.\"" + t + "\"")) {
                    rc.next();
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('"').append(t).append("\":").append(rc.getLong(1));
                    first = false;
                }
            }
            sb.append('}');
        }
        if (myPwd != null && !myPwd.isBlank()) {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true",
                    "root", myPwd);
                 Statement st = c.createStatement()) {
                sb.append(",\"mysql\":{");
                boolean first = true;
                try (ResultSet rs = st.executeQuery(
                        "SELECT table_name, table_rows FROM information_schema.tables "
                                + "WHERE table_schema='ai_db' ORDER BY table_name")) {
                    while (rs.next()) {
                        if (!first) {
                            sb.append(',');
                        }
                        sb.append('"').append(rs.getString(1)).append("\":").append(rs.getLong(2));
                        first = false;
                    }
                }
                sb.append('}');
            }
        } else {
            sb.append(",\"mysql\":\"MYSQL_PASSWORD_MISSING\"");
        }
        sb.append('}');
        System.out.println(sb);
    }
}
