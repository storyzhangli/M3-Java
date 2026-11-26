import java.sql.*;
public class D43Q3 {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SHOW COLUMNS FROM knowledge_document");
            while (rs.next()) System.out.println("COL " + rs.getString(1) + " " + rs.getString(2));
            rs = st.executeQuery("SELECT version, content_length, status, CHAR_LENGTH(content_hash) hl FROM knowledge_document WHERE document_id='D43E2E-PIPE-001'");
            while (rs.next()) System.out.println("ROW v" + rs.getInt(1) + " len=" + rs.getInt(2) + " " + rs.getString(3) + " hashLen=" + rs.getInt(4));
            rs = st.executeQuery("SELECT version, COUNT(*) FROM document_chunk_index WHERE document_id='D43E2E-PIPE-001' GROUP BY version");
            while (rs.next()) System.out.println("CHUNK v" + rs.getInt(1) + " n=" + rs.getInt(2));
            rs = st.executeQuery("SELECT version, paragraph_index, LEFT(content,40) FROM document_chunk_index WHERE document_id='D43E2E-PIPE-001' ORDER BY version, paragraph_index");
            while (rs.next()) System.out.println("C v" + rs.getInt(1) + "#" + rs.getInt(2) + " " + rs.getString(3).replace("\n","\\n"));
        }
    }
}
