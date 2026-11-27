import java.sql.*;
public class QAll { public static void main(String[] a) throws Exception {
  try (Connection c = DriverManager.getConnection(a[0],a[1],a[2]); Statement s = c.createStatement();
       ResultSet r = s.executeQuery("SELECT document_id, lifecycle_status, status, version FROM knowledge_document ORDER BY document_id")) {
    while (r.next()) System.out.println(r.getString(1)+" | "+r.getString(2)+" | "+r.getString(3)+" | v"+r.getInt(4));
  } } }
