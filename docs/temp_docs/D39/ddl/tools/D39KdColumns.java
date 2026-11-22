import java.sql.*;

/**
 * D39 P2：knowledge_document 追加权限三列（幂等：information_schema 探测后条件 ALTER）。
 * MySQL 8.0 无 ADD COLUMN IF NOT EXISTS 语法，先查后改是标准幂等实现。
 * 遗留行保持 NULL（D3 裁决，禁补值）。×2 重跑零报错零数据变化（C03）。
 * 密码仅环境变量 MYSQL_PASSWORD。
 */
public final class D39KdColumns {

    private static final String[][] COLUMNS = {
            {"tenant_id", "VARCHAR(64) NULL COMMENT 'D39 权限：租户（NULL=遗留无标签，权限端点不可见，D3）'"},
            {"dept_id", "VARCHAR(64) NULL COMMENT 'D39 权限：部门（''*''=租户内不限部门；NULL=无标签）'"},
            {"device_id", "VARCHAR(64) NULL COMMENT 'D39 权限：设备（''*''=不限设备；NULL=无标签）'"},
    };

    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String pass = System.getenv("MYSQL_PASSWORD");
        if (pass == null || pass.isBlank()) {
            throw new IllegalStateException("ENV_MISSING:MYSQL_PASSWORD");
        }
        for (int run = 1; run <= 2; run++) {
            try (Connection c = DriverManager.getConnection(url, "root", pass)) {
                long totalBefore = countAll(c);
                long nullBefore = countLegacyNullTenant(c); // -1=列尚未创建（首轮）
                for (String[] col : COLUMNS) {
                    if (!hasColumn(c, col[0])) {
                        try (Statement st = c.createStatement()) {
                            st.execute("ALTER TABLE knowledge_document ADD COLUMN " + col[0] + " " + col[1]);
                            System.out.println("[RUN" + run + "] ADD COLUMN " + col[0]);
                        }
                    } else {
                        System.out.println("[RUN" + run + "] COLUMN_EXISTS " + col[0] + " (idempotent skip)");
                    }
                }
                long totalAfter = countAll(c);
                long nullAfter = countLegacyNullTenant(c);
                // 幂等+零数据变化：行数不变；新建列后全部行=NULL（禁补值 D3）；二跑保持原 NULL 计数
                if (totalBefore != totalAfter) {
                    throw new IllegalStateException("ROW_COUNT_CHANGED " + totalBefore + "->" + totalAfter);
                }
                if (nullBefore == -1 && nullAfter != totalAfter) {
                    throw new IllegalStateException("NEW_COLUMNS_NOT_ALL_NULL nulls=" + nullAfter + " rows=" + totalAfter);
                }
                if (nullBefore >= 0 && nullBefore != nullAfter) {
                    throw new IllegalStateException("LEGACY_NULL_COUNT_CHANGED " + nullBefore + "->" + nullAfter);
                }
                System.out.println("[RUN" + run + "] OK rows=" + totalAfter + " tenant_null=" + nullAfter + " (NULL 未补值)");
            }
        }
        System.out.println("[DONE] KD_COLUMNS_IDEMPOTENT_X2_OK");
    }

    private static boolean hasColumn(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='ai_db' AND table_name='knowledge_document' AND column_name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static long countAll(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM knowledge_document")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static long countLegacyNullTenant(Connection c) throws SQLException {
        boolean exists;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM information_schema.columns "
                     + "WHERE table_schema='ai_db' AND table_name='knowledge_document' AND column_name='tenant_id'")) {
            exists = rs.next() && rs.getInt(1) > 0;
        }
        if (!exists) {
            return -1;
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM knowledge_document WHERE tenant_id IS NULL")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }
}
