import java.sql.*;

// D43E2EClean - E2E namespace sweep with before/after counts, order-safe.
// MySQL: delete via document_id (where the column exists) OR via id join (table_row -> extract).
// PG: metadata JSON key is camelCase 'documentId' (Spring AI PgVectorStore), NOT document_id.
// Never touches KP2 seeding corpus (DOC-xxx) or legacy frozen surface counts.
// Usage: java D43E2EClean <mysqlUrl> <mysqlUser> <mysqlPw> <pgUrl> <pgUser> <pgPw>
public class D43E2EClean {
    static final String LIKE = "D43E2E%";
    // order matters: children before parents (row -> extract -> rest)
    static final String[] MY_TABLES = {
            "d43_table_row", "d43_table_extract",
            "knowledge_document_page", "document_chunk_index", "document_vector_index",
            "d43_document_sync", "d43_admin_audit", "knowledge_document" };

    public static void main(String[] a) throws Exception {
        long pgAfter = -1;
        try (Connection m = DriverManager.getConnection(a[0], a[1], a[2])) {
            for (String t : MY_TABLES) {
                if (!tableExists(m, t)) { System.out.println("SKIP " + t + " (table absent)"); continue; }
                String sql = deleteSql(m, t);
                if (sql == null) { System.out.println("SKIP " + t + " (no reachable document key)"); continue; }
                long before = count(m, t);
                int del;
                try (Statement st = m.createStatement()) { del = st.executeUpdate(sql); }
                System.out.println("MY " + t + " before=" + before + " deleted=" + del + " after=" + count(m, t));
            }
        }
        try (Connection p = DriverManager.getConnection(a[3], a[4], a[5])) {
            long pgBefore = pgCount(p);
            int del;
            try (Statement st = p.createStatement()) {
                del = st.executeUpdate("DELETE FROM d43_vector_store WHERE metadata ->> 'documentId' LIKE '" + LIKE + "'");
            }
            pgAfter = pgCount(p);
            try (Statement st = p.createStatement()) {
                System.out.println("PG d43_vector_store e2eBefore=" + pgBefore + " deleted=" + del + " e2eAfter=" + pgAfter
                        + " totalAfter=" + one(st, "SELECT COUNT(*) FROM d43_vector_store"));
            }
        }
        boolean clean = pgAfter == 0;
        System.out.println(clean ? "E2E-CLEAN OK" : "E2E-CLEAN RESIDUE");
        System.exit(clean ? 0 : 2);
    }

    static String deleteSql(Connection m, String t) throws SQLException {
        if (hasCol(m, t, "document_id")) {
            return "DELETE FROM " + t + " WHERE document_id LIKE '" + LIKE + "'";
        }
        if (hasCol(m, t, "table_id")) { // d43_table_row
            return "DELETE r FROM " + t + " r JOIN d43_table_extract e ON r.table_id = e.id WHERE e.document_id LIKE '" + LIKE + "'";
        }
        return null;
    }

    static long count(Connection c, String t) throws SQLException {
        boolean byDoc = hasCol(c, t, "document_id");
        String sql = byDoc ? "SELECT COUNT(*) FROM " + t + " WHERE document_id LIKE '" + LIKE + "'"
                : (hasCol(c, t, "table_id")
                    ? "SELECT COUNT(*) FROM " + t + " r JOIN d43_table_extract e ON r.table_id = e.id WHERE e.document_id LIKE '" + LIKE + "'"
                    : "SELECT COUNT(*) FROM " + t);
        try (Statement st = c.createStatement()) { return one(st, sql); }
    }

    static long pgCount(Connection p) throws SQLException {
        try (Statement st = p.createStatement()) {
            return one(st, "SELECT COUNT(*) FROM d43_vector_store WHERE metadata ->> 'documentId' LIKE '" + LIKE + "'");
        }
    }

    static boolean tableExists(Connection c, String t) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, t, null)) { return rs.next(); }
    }

    static boolean hasCol(Connection c, String t, String col) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, t, col)) { return rs.next(); }
    }

    static long one(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
}
