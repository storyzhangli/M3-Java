import java.sql.*;
public final class D40LegacyRows {
    public static void main(String[] a) throws Exception {
        String url="jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        try(Connection c=DriverManager.getConnection(url,"root",System.getenv("MYSQL_PASSWORD"));
            Statement st=c.createStatement()){
            System.out.println("== document_vector_index rows ==");
            try(ResultSet rs=st.executeQuery("SELECT document_id, version, status, strategy, chunk_count FROM document_vector_index ORDER BY document_id, version")){
                while(rs.next()) System.out.println(rs.getString(1)+" v="+rs.getInt(2)+" "+rs.getString(3)+" "+rs.getString(4)+" chunks="+rs.getInt(5));
            }
            System.out.println("== document_chunk_index prefix counts detail ==");
            try(ResultSet rs=st.executeQuery("SELECT document_id, count(*) n FROM document_chunk_index GROUP BY document_id ORDER BY document_id")){
                while(rs.next()) System.out.println(rs.getString(1)+" rows="+rs.getInt(2));
            }
        }
    }
}
