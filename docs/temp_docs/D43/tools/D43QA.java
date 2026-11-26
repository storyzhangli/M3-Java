import java.sql.*;
public class D43QA {
  public static void main(String[] x) throws Exception {
    try (Connection c = DriverManager.getConnection(x[0], x[1], x[2]); Statement s = c.createStatement()) {
      ResultSet rs = s.executeQuery("SELECT metadata->>'documentId' d, metadata->>'version' v, metadata->>'chunkIndex' ci, metadata->>'splitVersion' sv, id FROM d43_vector_store WHERE metadata->>'documentId' LIKE 'D43E2E%' ORDER BY d, v, ci");
      while (rs.next()) System.out.println("PG " + rs.getString(1) + " v" + rs.getString(2) + " ci=" + rs.getString(3) + " sv=" + rs.getString(4) + " id=" + rs.getString(5));
    }
  }
}
