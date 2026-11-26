import java.sql.*;
public class D43QF {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("SHOW COLUMNS FROM d43_document_sync");
      StringBuilder cols = new StringBuilder();
      while (r.next()) cols.append(r.getString(1)).append(' ');
      System.out.println("sync columns: " + cols);
      for (int i = 0; i < 4; i++) {
        ResultSet x = s.executeQuery("SELECT (SELECT COUNT(*) FROM knowledge_document), (SELECT COUNT(*) FROM document_chunk_index WHERE document_id LIKE 'D43E2E%'), (SELECT COUNT(*) FROM d43_document_sync)");
        x.next();
        System.out.println("T+" + (i*45) + "s kd=" + x.getString(1) + " e2eChunk=" + x.getString(2) + " syncN=" + x.getString(3));
        x = s.executeQuery("SELECT * FROM d43_document_sync");
        ResultSetMetaData m = x.getMetaData();
        while (x.next()) { StringBuilder b=new StringBuilder("  SYNC "); for(int k=1;k<=m.getColumnCount();k++) b.append(m.getColumnLabel(k)).append('=').append(x.getString(k)).append(" | "); System.out.println(b); }
        if (i < 3) Thread.sleep(45000);
      }
    }
  }
}
