import java.sql.*;
public class D43QC {
  static void dump(Connection c, String label, String sql) throws Exception {
    System.out.println("== " + label);
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
      ResultSetMetaData m = r.getMetaData();
      int n = 0;
      while (r.next()) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= m.getColumnCount(); i++) b.append(m.getColumnLabel(i)).append('=').append(r.getString(i)).append(" | ");
        System.out.println(b);
        n++;
      }
      if (n == 0) System.out.println("(empty)");
    }
  }
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2])) {
      dump(c, "knowledge_document E2E", "SELECT document_id,status,version FROM knowledge_document WHERE document_id LIKE 'D43E2E%' ORDER BY document_id");
      dump(c, "chunk_index E2E docs", "SELECT DISTINCT document_id FROM document_chunk_index WHERE document_id LIKE 'D43E2E%' ORDER BY 1");
      dump(c, "vector_index E2E", "SELECT document_id,index_status FROM document_vector_index WHERE document_id LIKE 'D43E2E%' ORDER BY 1");
      dump(c, "sync E2E", "SELECT document_id,operation_type,status FROM d43_document_sync WHERE document_id LIKE 'D43E2E%' ORDER BY 1");
      dump(c, "extract E2E docs", "SELECT DISTINCT document_id FROM d43_table_extract WHERE document_id LIKE 'D43E2E%' ORDER BY 1");
      dump(c, "audit rows", "SELECT COUNT(*) cnt FROM d43_admin_audit");
    }
  }
}
