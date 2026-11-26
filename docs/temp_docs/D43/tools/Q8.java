import java.sql.*;
public class Q8 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true","root",x[0]);
      Statement s=c.createStatement()) {
   System.out.println("== knowledge_document by prefix ==");
   ResultSet r=s.executeQuery("SELECT SUBSTRING_INDEX(document_id,'-',2) pfx, COUNT(*) n, GROUP_CONCAT(DISTINCT lifecycle_status) lc FROM knowledge_document GROUP BY pfx ORDER BY n DESC");
   while (r.next()) System.out.println("KD "+r.getString(1)+" n="+r.getString(2)+" lc="+r.getString(3));
   System.out.println("== chunk orphans detail ==");
   ResultSet o=s.executeQuery("SELECT document_id, COUNT(*) n FROM document_chunk_index WHERE document_id NOT IN (SELECT document_id FROM knowledge_document) GROUP BY document_id");
   while (o.next()) System.out.println("ORPH "+o.getString(1)+" n="+o.getString(2));
   System.out.println("== sync rows ==");
   ResultSet y=s.executeQuery("SELECT document_id, version, status, reason_code FROM d43_document_sync ORDER BY id DESC LIMIT 6");
   while (y.next()) System.out.println("SYNC "+y.getString(1)+" v"+y.getString(2)+" "+y.getString(3)+" "+y.getString(4));
 } } }
