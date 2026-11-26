import java.sql.*;
public class D43Q7 {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement st = c.createStatement()) {
      int n = st.executeUpdate("DELETE r FROM d43_table_row r LEFT JOIN d43_table_extract e ON r.table_id = e.id WHERE e.id IS NULL");
      ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM d43_table_row"); rs.next();
      System.out.println("ORPHAN-ROW deleted=" + n + " remain=" + rs.getLong(1));
    }
  }
}
