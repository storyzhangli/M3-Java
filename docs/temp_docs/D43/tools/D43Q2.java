import java.sql.*;
public class D43Q2 {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT document_id, version, status, content_hash FROM knowledge_document WHERE document_id LIKE 'D43E2E%' ORDER BY document_id, version");
            while (rs.next()) System.out.println(rs.getString(1) + " v" + rs.getInt(2) + " " + rs.getString(3) + " hash=" + String.valueOf(rs.getString(4)));
            System.out.println("--chunks--");
            rs = st.executeQuery("SELECT document_id, version, COUNT(*) FROM document_chunk_index WHERE document_id LIKE 'D43E2E%' GROUP BY document_id, version");
            while (rs.next()) System.out.println(rs.getString(1) + " v" + rs.getInt(2) + " chunks=" + rs.getInt(3));
            System.out.println("--vector_index meta--");
            rs = st.executeQuery("SELECT document_id, version, status, COUNT(*) FROM document_vector_index WHERE document_id LIKE 'D43E2E%' GROUP BY document_id, version, status");
            while (rs.next()) System.out.println(rs.getString(1) + " v" + rs.getInt(2) + " " + rs.getString(3) + " n=" + rs.getInt(4));
        }
    }
}

