import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * D43 KP2 一次性盘点补充：d43_ 表存在性（information_schema 大小写不敏感匹配，
 * 修正 D42VectorProbe 链式改编遗留的 LIKE 'd43\_%' ESCAPE 在 Linux MySQL lower_case
 * 环境返回 0 的误报）+ 遗留 chunk 归属 + 空语料指纹常量自证。
 * 用法: java D43Kp2Extra <mysqlUrl> <user> <pass>
 */
public class D43Kp2Extra {
    public static void main(String[] args) throws Exception {
        String url = args[0], user = args[1], pass = args[2];
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT table_name, table_rows FROM information_schema.tables " +
                     "WHERE LOWER(table_schema)='ai_db' AND LOWER(table_name) LIKE 'd43\\_%' ESCAPE '\\\\' ORDER BY table_name")) {
                System.out.println("== d43_ tables ==");
                int n = 0;
                while (rs.next()) { System.out.println("  " + rs.getString(1) + " approxRows=" + rs.getLong(2)); n++; }
                System.out.println("D43_TABLES=" + n);
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT document_id, COUNT(*) FROM document_chunk_index " +
                     "WHERE document_id LIKE 'DOC-%' GROUP BY document_id")) {
                System.out.println("== legacy DOC-* chunk rows ==");
                while (rs.next()) System.out.println("  " + rs.getString(1) + " = " + rs.getLong(2));
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SHOW VARIABLES LIKE 'lower_case_table_names'")) {
                while (rs.next()) System.out.println("lower_case_table_names=" + rs.getString(2));
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@version")) {
                if (rs.next()) System.out.println("mysql_version=" + rs.getString(1));
            }
            // 空集 SHA-256 常量自证（= PG 0 行 FINGERPRINT 应为 e3b0c442… 的口径依据）
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            System.out.println("EMPTY_SHA256=" + HexFormat.of().formatHex(md.digest(new byte[0])));
        }
    }
}
