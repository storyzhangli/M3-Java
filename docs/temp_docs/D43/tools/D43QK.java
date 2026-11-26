import java.sql.*;
/** D43QK — PG d43_vector_store 全表盘点与受控清理：无 mode=列全表 doc/version 分布；mode=DEL:<documentId>=删除该行族（仅 E2E/orphan 裁决后用）。 */
public class D43QK {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      if (a.length > 3 && a[3].startsWith("DEL:")) {
        String doc = a[3].substring(4);
        int n = s.executeUpdate("DELETE FROM d43_vector_store WHERE metadata->>'documentId' = '" + doc.replace("'","''") + "'");
        System.out.println("DELETED doc=" + doc + " rows=" + n);
      }
      ResultSet r = s.executeQuery("SELECT metadata->>'documentId' doc, COUNT(*) n FROM d43_vector_store GROUP BY 1 ORDER BY 1");
      int total = 0;
      while (r.next()) { System.out.println("DOC " + r.getString(1) + " n=" + r.getInt(2)); total += r.getInt(2); }
      System.out.println("TOTAL=" + total);
    }
  }
}
