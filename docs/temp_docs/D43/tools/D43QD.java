import java.sql.*;
public class D43QD {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      for (String t : new String[]{"knowledge_document","document_vector_index"}) {
        System.out.println("== columns " + t);
        try (ResultSet r = s.executeQuery("SHOW COLUMNS FROM " + t)) { while (r.next()) System.out.print(r.getString(1) + " "); }
        System.out.println();
      }
      System.out.println("== knowledge_document ALL ids");
      try (ResultSet r = s.executeQuery("SELECT document_id FROM knowledge_document ORDER BY document_id")) {
        while (r.next()) System.out.println(r.getString(1));
      }
      System.out.println("== document_vector_index ALL");
      try (ResultSet r = s.executeQuery("SELECT * FROM document_vector_index ORDER BY document_id")) {
        ResultSetMetaData m = r.getMetaData();
        while (r.next()) { StringBuilder b=new StringBuilder(); for(int i=1;i<=m.getColumnCount();i++) b.append(m.getColumnLabel(i)).append('=').append(r.getString(i)).append(" | "); System.out.println(b); }
      }
    }
  }
}
