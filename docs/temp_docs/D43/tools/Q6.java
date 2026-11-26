import java.sql.*;
public class Q6 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true","root",x[0]);
      Statement s=c.createStatement()) {
   ResultSet r=s.executeQuery("SELECT document_id, created_at, updated_at, source, source_system FROM knowledge_document WHERE document_id LIKE 'DOC-%'");
   while (r.next()) System.out.println("KD "+r.getString(1)+" created="+r.getString(2)+" updated="+r.getString(3)+" src="+r.getString(4)+"/"+r.getString(5));
   System.out.println("-- audit 面 DEPREC/lifecycle 相关 --");
   ResultSet a=s.executeQuery("SELECT id, action, target, operator, created_at FROM d43_admin_audit WHERE (action LIKE '%LIFECYCLE%' OR action LIKE '%DEPREC%' OR target LIKE 'DOC-%') ORDER BY id DESC LIMIT 12");
   while (a.next()) System.out.println("AU id="+a.getString(1)+" act="+a.getString(2)+" tgt="+a.getString(3)+" op="+a.getString(4)+" at="+a.getString(5));
 } } }
