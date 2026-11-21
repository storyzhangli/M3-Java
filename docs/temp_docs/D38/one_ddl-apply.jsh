// one_ddl-apply.jsh —— D38 P2：执行 one_ MySQL DDL + 既有表结构前后快照（C05 证据）
// 用法：jshell --class-path <mysql-connector-jar> one_ddl-apply.jsh
// 密码经环境变量 MYSQL_PASSWORD 注入（默认 123456 本机开发），不落盘不回显。
import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

String repo = "E:/AI/project/AI-Learning-one/M3-Java";
String ddlDir = repo + "/docs/evidence/D38/ddl";
Files.createDirectories(Path.of(ddlDir));
String pwd = System.getenv("MYSQL_PASSWORD");
if (pwd == null || pwd.isBlank()) pwd = "123456";
String url = "jdbc:mysql://127.0.0.1:3306/ai_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

String[] existingTables = {"document_vector_index", "document_chunk_index", "knowledge_document", "knowledge_document_page"};

String snapshot(Connection conn, String[] tables) throws Exception {
    StringBuilder sb = new StringBuilder();
    for (String t : tables) {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW CREATE TABLE `" + t + "`")) {
            if (rs.next()) sb.append("-- ").append(t).append("\n").append(rs.getString(2)).append(";\n\n");
        }
    }
    return sb.toString();
}

try (Connection conn = DriverManager.getConnection(url, "root", pwd)) {
    // 1) 既有表 before 快照
    String before = snapshot(conn, existingTables);
    Files.writeString(Path.of(ddlDir, "existing-tables-before.sql"), before, StandardCharsets.UTF_8);
    System.out.println("BEFORE_SNAPSHOT_OK tables=" + existingTables.length);

    // 2) 执行 one_ DDL（按分号拆分，剔除 -- 注释行）
    String ddl = Files.readString(Path.of(repo + "/D38/D38_QWE_MROQRE/src/main/resources/db/schema-one-document-vector-index.sql"), StandardCharsets.UTF_8);
    StringBuilder cleaned = new StringBuilder();
    for (String line : ddl.split("\n")) {
        if (line.trim().startsWith("--")) continue;
        cleaned.append(line).append("\n");
    }
    int executed = 0;
    for (String stmt : cleaned.toString().split(";")) {
        String s = stmt.trim();
        if (s.isEmpty()) continue;
        try (Statement st = conn.createStatement()) { st.execute(s); executed++; }
    }
    System.out.println("DDL_EXECUTED statements=" + executed);

    // 3) one_ 表结构快照 + 既有表 after 快照
    String oneTables = snapshot(conn, new String[]{"one_document_vector_index", "one_document_chunk_index"});
    Files.writeString(Path.of(ddlDir, "one-tables-create.sql"), oneTables, StandardCharsets.UTF_8);
    String after = snapshot(conn, existingTables);
    Files.writeString(Path.of(ddlDir, "existing-tables-after.sql"), after, StandardCharsets.UTF_8);
    boolean unchanged = before.equals(after);
    Files.writeString(Path.of(ddlDir, "existing-tables-unchanged.txt"), "UNCHANGED=" + unchanged + "\n", StandardCharsets.UTF_8);
    System.out.println("EXISTING_TABLES_UNCHANGED=" + unchanged);

    // 4) 共享文档表语料 ACTIVE 行核验（one_ 轨复用，不重复登记）
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT document_id, lifecycle_status FROM knowledge_document WHERE document_id LIKE 'DOC-%' ORDER BY document_id")) {
        while (rs.next()) System.out.println("CORPUS_DOC " + rs.getString(1) + " lifecycle=" + rs.getString(2));
    }
}
System.out.println("P2_MYSQL_DDL_DONE");
/exit