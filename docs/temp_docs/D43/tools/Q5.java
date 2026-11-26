import java.sql.*;
public class Q5 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true","root",x[0]);
      Statement s=c.createStatement();
      ResultSet r=s.executeQuery("SELECT document_id, status, lifecycle_status, version, content_hash FROM knowledge_document WHERE document_id LIKE 'DOC-%' ORDER BY document_id")) {
   while (r.next()) System.out.println("KD "+r.getString(1)+" status="+r.getString(2)+" lifecycle="+r.getString(3)+" v="+r.getString(4)+" hash="+r.getString(5)); } } }
