import java.sql.*;
public class Q2 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection("jdbc:postgresql://192.168.200.153:5432/spring_ai", x[0], x[1]);
      Statement s=c.createStatement();
      ResultSet r=s.executeQuery("SELECT id, metadata->>''chunkId' c, metadata->>''documentId' d FROM d43_vector_store WHERE metadata->>''documentId' LIKE 'DOC%' ORDER BY id LIMIT 12")) {
  while (r.next()) System.out.println("PG id="+r.getString(1)+" chunkMeta="+r.getString(2)+" doc="+r.getString(3)); } } }
