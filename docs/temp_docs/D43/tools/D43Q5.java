import java.sql.*;
public class D43Q5 {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement st = c.createStatement()) {
      String[] t = {"knowledge_document","knowledge_document_page","document_chunk_index","document_vector_index","d43_document_sync","d43_table_extract"};
      for (String x : t) { try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + x)) { rs.next(); System.out.println("NOW " + x + " = " + rs.getLong(1)); } }
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM d43_table_row")) { rs.next(); System.out.println("NOW d43_table_row = " + rs.getLong(1)); }
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM d43_auth_principal")) { rs.next(); System.out.println("NOW d43_auth_principal = " + rs.getLong(1)); }
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM d43_admin_audit")) { rs.next(); System.out.println("NOW d43_admin_audit = " + rs.getLong(1)); }
      System.out.println("--- git log -6 ---");
    }
  }
}
