import java.sql.*;
public class D43Q9 {
  public static void main(String[] x) throws Exception {
    try (Connection c = DriverManager.getConnection(x[0], x[1], x[2]); Statement s = c.createStatement()) {
      ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='ai_db' AND table_name='d43_table_extract' ORDER BY ordinal_position");
      StringBuilder b = new StringBuilder("EXTRACT_COLS ");
      while (rs.next()) b.append(rs.getString(1)).append(',');
      System.out.println(b);
      rs = s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='ai_db' AND table_name='d43_table_row' ORDER BY ordinal_position");
      StringBuilder b2 = new StringBuilder("ROW_COLS ");
      while (rs.next()) b2.append(rs.getString(1)).append(',');
      System.out.println(b2);
    }
  }
}
