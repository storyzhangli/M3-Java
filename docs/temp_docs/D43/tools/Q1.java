import java.sql.*;
public class Q1 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true", x[0], x[1]);
      Statement s=c.createStatement();
      ResultSet r=s.executeQuery("SELECT chunk_id, document_id, version, status FROM document_chunk_index WHERE document_id LIKE 'DOC-CNC%' OR document_id LIKE 'DOC-SERVO%' OR document_id LIKE 'DOC-WORKORDER%' ORDER BY chunk_id LIMIT 12")) {
  while (r.next()) System.out.println("MY "+r.getString(1)+" <- "+r.getString(2)+" v"+r.getString(3)+" "+r.getString(4)); } } }
