import java.sql.*;

public class D43Q4 {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT document_id, version, status, stage, error_code, error_message, chunk_count FROM document_vector_index WHERE document_id LIKE 'D43E2E%' ORDER BY document_id, version");
            while (rs.next()) System.out.println("VI " + rs.getString(1) + " v" + rs.getInt(2) + " " + rs.getString(3) + "/" + rs.getString(4) + " ec=" + rs.getString(5) + " em=" + rs.getString(6) + " cc=" + rs.getInt(7));
            rs = st.executeQuery("SELECT document_id, COUNT(*) FROM document_chunk_index WHERE document_id LIKE 'D43E2E%' GROUP BY document_id");
            while (rs.next()) System.out.println("CHUNKCNT " + rs.getString(1) + " n=" + rs.getInt(2));
            rs = st.executeQuery("SELECT document_id, status, version, LEFT(content_hash,12) FROM knowledge_document WHERE document_id LIKE 'D43E2E-TBL%' ORDER BY document_id");
            while (rs.next()) System.out.println("TBLDOC " + rs.getString(1) + " " + rs.getString(2) + " v" + rs.getInt(3) + " h=" + rs.getString(4));
        }
    }
}
