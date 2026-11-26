import java.sql.*;
public class Q10 { public static void main(String[] x) throws Exception {
 try (Connection c=DriverManager.getConnection(x[0],"root",x[1]); Statement s=c.createStatement()) {
  String[] ts={"knowledge_document","document_chunk_index","document_vector_index","d43_document_sync","d43_table_row","d43_table_extract","d43_admin_audit"};
  for (String t: ts){ try (ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+t)){ r.next(); System.out.println("TOTAL "+t+"="+r.getInt(1)); } }
  try (ResultSet r=s.executeQuery("SELECT COUNT(*) FROM document_chunk_index WHERE document_id NOT IN (SELECT document_id FROM knowledge_document)")){ r.next(); System.out.println("ORPHAN(legacy d32eval 不触碰)="+r.getInt(1)); }
 } } }
