import java.sql.*;
public class D43QJ {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
      ResultSet r = s.executeQuery("SELECT id, metadata->>'documentId' doc, metadata->>'version' v, metadata->>'tenantId' t, metadata->>'deptId' d, metadata->>'deviceId' dev, LEFT(content,30) c FROM d43_vector_store WHERE metadata->>'documentId' LIKE 'D43E2E-MX%' OR content LIKE '%MXKELVIN42%'");
      while (r.next()) System.out.println("PG id="+r.getString(1).substring(0,8)+" doc="+r.getString(2)+" v="+r.getString(3)+" t="+r.getString(4)+" d="+r.getString(5)+" dev="+r.getString(6)+" c="+r.getString(7));
    }
  }
}
