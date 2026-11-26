import java.sql.*;
public class D43Q6 {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("SELECT jsonb_object_keys(metadata::jsonb) k FROM d43_vector_store LIMIT 8")) {
        while (rs.next()) System.out.println("KEY " + rs.getString(1)); }
      try (ResultSet rs = st.executeQuery("SELECT DISTINCT metadata->>'document_id' md, metadata->>'documentId' mc, COUNT(*) n FROM d43_vector_store GROUP BY 1,2 ORDER BY 1")) {
        while (rs.next()) System.out.println("DOC md=" + rs.getString(1) + " mc=" + rs.getString(2) + " n=" + rs.getInt(3)); }
      try (ResultSet rs = st.executeQuery("SELECT id, LEFT(metadata::text,120) FROM d43_vector_store LIMIT 3")) {
        while (rs.next()) System.out.println("ROW " + rs.getString(1) + " " + rs.getString(2)); }
    }
  }
}
