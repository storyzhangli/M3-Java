import java.sql.*;
public class Q9 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true","root",x[0]);
      Statement s=c.createStatement()) {
   ResultSet cs=s.executeQuery("SHOW COLUMNS FROM d43_document_sync"); StringBuilder sb=new StringBuilder();
   while(cs.next()) sb.append(cs.getString(1)).append(","); System.out.println("SYNC-COLS "+sb);
   ResultSet y=s.executeQuery("SELECT document_id, version, sync_state, reason_code FROM d43_document_sync LIMIT 6");
   while (y.next()) System.out.println("SYNC "+y.getString(1)+" v"+y.getString(2)+" "+y.getString(3)+" "+y.getString(4));
 } } }
