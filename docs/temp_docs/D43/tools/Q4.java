import java.sql.*;
public class Q4 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true","root",x[0]);
      Statement s=c.createStatement()) {
   ResultSet cs=s.executeQuery("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='ai_db' AND TABLE_NAME='knowledge_document'");
   StringBuilder sb=new StringBuilder(); while(cs.next()) sb.append(cs.getString(1)).append(",");
   System.out.println("COLS: "+sb);
   ResultSet r=s.executeQuery("SELECT document_id, status, current_version, is_current FROM knowledge_document WHERE document_id IN ('DOC-CNC-COOLING-001','DOC-SERVO-TROUBLE-002','DOC-WORKORDER-SPEC-003')");
   while (r.next()) System.out.println("KD "+r.getString(1)+" status="+r.getString(2)+" cur="+r.getString(3)+" isc="+r.getString(4));
 } } }
