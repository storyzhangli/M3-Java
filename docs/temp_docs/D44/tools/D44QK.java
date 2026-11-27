import java.sql.*;
/** D44QK — PG 向量表盘点与受控清理。用法: java D44QK <pgUrl> <user> <pass> <table> [DEL:<documentId>] */
public class D44QK {
  public static void main(String[] a) throws Exception {
    String table = a[3];
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      if (a.length > 4 && a[4].startsWith("DEL:")) {
        String doc = a[4].substring(4);
        int n = s.executeUpdate("DELETE FROM " + table + " WHERE metadata->>'documentId' = '" + doc.replace("'","''") + "'");
        System.out.println("DELETED doc=" + doc + " rows=" + n);
      }
      ResultSet r = s.executeQuery("SELECT metadata->>'documentId' doc, COUNT(*) n FROM " + table + " GROUP BY 1 ORDER BY 1");
      int total = 0;
      while (r.next()) { System.out.println("DOC " + r.getString(1) + " n=" + r.getInt(2)); total += r.getInt(2); }
      System.out.println("TABLE=" + table + " TOTAL=" + total);
    }
  }
}
