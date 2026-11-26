import java.sql.*;
public class D43QG {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      System.out.println("== audit rows (baseline expect 0)");
      ResultSet r = s.executeQuery("SELECT * FROM d43_admin_audit ORDER BY id");
      ResultSetMetaData m = r.getMetaData();
      while (r.next()) { StringBuilder b=new StringBuilder(); for(int i=1;i<=m.getColumnCount();i++) b.append(m.getColumnLabel(i)).append('=').append(r.getString(i)).append(" | "); System.out.println(b); }
      System.out.println("== frozen set check: 19 expected non-E2E doc ids present?");
      r = s.executeQuery("SELECT document_id FROM knowledge_document WHERE document_id NOT LIKE 'd43e2e%' ORDER BY document_id");
      int n=0; while (r.next()) n++;
      System.out.println("nonE2E rows=" + n);
      System.out.println("== D41/R03 冻结 16 + DOC 3 完整性（缺谁）");
      r = s.executeQuery("SELECT CONCAT('D41-R03RE-0',x) FROM (SELECT 8 x UNION SELECT 9 UNION SELECT 10 UNION SELECT 11 UNION SELECT 12 UNION SELECT 13 UNION SELECT 14) t WHERE CONCAT('D41-R03RE-0',x) NOT IN (SELECT document_id FROM knowledge_document)");
      while (r.next()) System.out.println("MISSING: " + r.getString(1));
      r = s.executeQuery("SELECT document_id FROM knowledge_document WHERE document_id LIKE 'D43E2E%' OR document_id LIKE 'd43e2e%'");
      System.out.println("== current E2E-ish ids");
      while (r.next()) System.out.println(r.getString(1));
    }
  }
}
