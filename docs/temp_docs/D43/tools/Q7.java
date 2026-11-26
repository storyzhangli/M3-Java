import java.sql.*;
public class Q7 { public static void main(String[] x) throws Exception {
 String ids = "'DOC-CNC-COOLING-001','DOC-SERVO-TROUBLE-002','DOC-WORKORDER-SPEC-003'";
 try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true","root",x[0]);
      Statement s=c.createStatement()) {
   ResultSet r=s.executeQuery("SELECT document_id, lifecycle_status FROM knowledge_document WHERE document_id IN ("+ids+")");
   while (r.next()) System.out.println("BEFORE "+r.getString(1)+" "+r.getString(2));
   int n=s.executeUpdate("UPDATE knowledge_document SET lifecycle_status='ACTIVE' WHERE document_id IN ("+ids+") AND lifecycle_status='DEPRECATED'");
   System.out.println("UPDATED rows="+n);
   ResultSet r2=s.executeQuery("SELECT document_id, lifecycle_status, updated_at FROM knowledge_document WHERE document_id IN ("+ids+")");
   while (r2.next()) System.out.println("AFTER "+r2.getString(1)+" "+r2.getString(2)+" "+r2.getString(3));
 } } }
