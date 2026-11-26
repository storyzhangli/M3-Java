import java.sql.*;
public class D43QB {
  public static void main(String[] x) throws Exception {
    try (Connection c = DriverManager.getConnection(x[0], x[1], x[2]); Statement s = c.createStatement()) {
      ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='ai_db' AND table_name='document_chunk_index' ORDER BY ordinal_position");
      StringBuilder b = new StringBuilder("COLS ");
      while (rs.next()) b.append(rs.getString(1)).append(',');
      System.out.println(b);
      rs = s.executeQuery("SELECT version, chunk_index, strategy, chunk_id, CHAR_LENGTH(content) cl FROM document_chunk_index WHERE document_id='D43E2E-PIPE-001' ORDER BY chunk_index");
      while (rs.next()) System.out.println("MY v" + rs.getInt(1) + " ci=" + rs.getInt(2) + " st=" + rs.getString(3) + " id=" + rs.getString(4) + " len=" + rs.getInt(5));
    }
  }
}
