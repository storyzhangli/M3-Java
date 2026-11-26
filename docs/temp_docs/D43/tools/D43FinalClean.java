import java.sql.*;
/**
 * D43FinalClean — C18/C19 终态清理：本迭代 E2E/A 矩阵资产清零（knowledge_document D43-A* 族
 * 及其 chunk/vector-index/sync 关联行），legacy d32eval 24 孤行与冻结面不动。零 PG 触达。
 * 用法: java D43FinalClean <mysqlUrl> root <pw>
 */
public class D43FinalClean {
  static void tbl(Statement s, String label, String sel, String del) throws Exception {
    try (ResultSet r = s.executeQuery(sel)) { r.next(); int before = r.getInt(1); }
    int n = s.executeUpdate(del);
    try (ResultSet r = s.executeQuery(sel)) { r.next(); System.out.println(label + " before+deleted=" + n + " after=" + r.getInt(1)); }
  }
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      System.out.println("== sync rows BEFORE (定性留档) ==");
      try (ResultSet y = s.executeQuery("SELECT document_id, version, operation, sync_status, reason_code FROM d43_document_sync ORDER BY id")) {
        while (y.next()) System.out.println("SYNC " + y.getString(1) + " v" + y.getString(2) + " " + y.getString(3) + " " + y.getString(4) + " " + y.getString(5));
      }
      tbl(s, "KD  knowledge_document", "SELECT COUNT(*) FROM knowledge_document WHERE document_id LIKE 'D43-A%'",
             "DELETE FROM knowledge_document WHERE document_id LIKE 'D43-A%'");
      tbl(s, "CH  document_chunk_index", "SELECT COUNT(*) FROM document_chunk_index WHERE document_id LIKE 'D43-A%'",
             "DELETE FROM document_chunk_index WHERE document_id LIKE 'D43-A%'");
      tbl(s, "VI  document_vector_index", "SELECT COUNT(*) FROM document_vector_index WHERE document_id LIKE 'D43-A%'",
             "DELETE FROM document_vector_index WHERE document_id LIKE 'D43-A%'");
      tbl(s, "SY  d43_document_sync", "SELECT COUNT(*) FROM d43_document_sync WHERE document_id LIKE 'D43-A%'",
             "DELETE FROM d43_document_sync WHERE document_id LIKE 'D43-A%'");
      tbl(s, "TR  d43_table_row", "SELECT COUNT(*) FROM d43_table_row WHERE document_id LIKE 'D43-A%'",
             "DELETE FROM d43_table_row WHERE document_id LIKE 'D43-A%'");
      tbl(s, "TE  d43_table_extract", "SELECT COUNT(*) FROM d43_table_extract WHERE document_id LIKE 'D43-A%'",
             "DELETE FROM d43_table_extract WHERE document_id LIKE 'D43-A%'");
      System.out.println("== 终态全景 ==");
      String[] ts = {"knowledge_document", "document_chunk_index", "document_vector_index", "d43_document_sync", "d43_admin_audit"};
      for (String t : ts) try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + t)) { r.next(); System.out.println("TOTAL " + t + "=" + r.getInt(1)); }
      try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM document_chunk_index WHERE document_id NOT IN (SELECT document_id FROM knowledge_document)")) {
        r.next(); System.out.println("ORPHAN-CHUNKS(legacy d32eval, 不触碰)=" + r.getInt(1)); }
      System.out.println("FINAL-CLEAN OK");
    }
  }
}
