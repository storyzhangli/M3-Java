import java.sql.*;
public class Q3 {
  static final String S = "\"";
  public static void main(String[] x) throws Exception {
    try (Connection c = DriverManager.getConnection("jdbc:postgresql://192.168.200.153:5432/spring_ai", x[0], x[1]);
         Statement s = c.createStatement();
         ResultSet r = s.executeQuery("SELECT id, substring(metadata::text from 'chunkId.{0,8}([0-9a-f]{8}-[0-9a-f-]{27})') cm, "
             + "substring(metadata::text from 'documentId.{0,8}(DOC-[A-Z0-9-]+)') d FROM d43_vector_store "
             + "WHERE metadata::text LIKE '%DOC-%' ORDER BY id")) {
      while (r.next()) System.out.println("PG id=" + r.getString(1) + " chunkMeta=" + r.getString(2) + " doc=" + r.getString(3));
    }
    try (Connection c2 = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true", "root", x[2]);
         Statement s2 = c2.createStatement();
         ResultSet r2 = s2.executeQuery("SELECT document_id, status, lifecycle_status, version FROM knowledge_document WHERE document_id LIKE 'DOC-%' ORDER BY document_id LIMIT 25")) {
      while (r2.next()) System.out.println("KD " + r2.getString(1) + " status=" + r2.getString(2) + " lifecycle=" + r2.getString(3) + " v=" + r2.getString(4));
    }
  }
}
