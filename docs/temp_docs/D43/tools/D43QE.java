import java.sql.*;
public class D43QE {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("SELECT COUNT(*) t, SUM(document_id IS NULL) n1, SUM(document_id='') n2 FROM knowledge_document");
      r.next(); System.out.println("total=" + r.getString(1) + " nullDocId=" + r.getString(2) + " emptyDocId=" + r.getString(3));
      r = s.executeQuery("SELECT id,document_id,title,source_system,status,version,content_hash FROM knowledge_document WHERE document_id IS NULL OR document_id=''");
      while (r.next()) System.out.println("ROW id=" + r.getString(1) + " | docId=" + r.getString(2) + " | title=" + r.getString(3) + " | src=" + r.getString(4) + " | st=" + r.getString(5) + " | v=" + r.getString(6) + " | hash=" + (r.getString(7)==null?"null":r.getString(7).substring(0,12)));
    }
  }
}
