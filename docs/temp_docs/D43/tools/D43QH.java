import java.sql.*;
public class D43QH {
  static void all(Statement s, String sql) throws Exception {
    ResultSet r = s.executeQuery(sql);
    ResultSetMetaData m = r.getMetaData();
    while (r.next()) { StringBuilder b=new StringBuilder(); for(int i=1;i<=m.getColumnCount();i++) b.append(m.getColumnLabel(i)).append('=').append(r.getString(i)).append(" | "); System.out.println(b); }
  }
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("SHOW COLUMNS FROM d43_auth_principal");
      StringBuilder cols = new StringBuilder("principal columns: ");
      while (r.next()) cols.append(r.getString(1)).append(' ');
      System.out.println(cols);
      System.out.println("== principals");
      all(s, "SELECT * FROM d43_auth_principal");
      System.out.println("== doc scopes");
      all(s, "SELECT document_id,tenant_id,dept_id,device_id,status FROM knowledge_document WHERE document_id LIKE 'D43E2E%' OR document_id LIKE 'DOC%' OR document_id='d43e2e-perm-a' OR document_id='d43e2e-perm-b' ORDER BY document_id");
    }
  }
}
