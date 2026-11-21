// one_pg-catalog.jsh —— D38 P2：PG catalog 独立核验（C05 证据）
// 核验 one_d38_vector_store vector(1024)/HNSW/cosine ops；local 表 d38_vector_store 结构不变。
import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

String repo = "E:/AI/project/AI-Learning-one/M3-Java";
String outDir = repo + "/docs/evidence/D38/ddl";
Files.createDirectories(Path.of(outDir));
String pwd = System.getenv("PG_VECTOR_PASSWORD");
if (pwd == null || pwd.isBlank()) { System.out.println("PG_PASSWORD_MISSING"); System.exit(2); }
String url = "jdbc:postgresql://192.168.200.153:5432/spring_ai";
StringBuilder json = new StringBuilder("{\n");
try (Connection c = DriverManager.getConnection(url, "postgres", pwd)) {
    // 扩展版本
    try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT extversion FROM pg_extension WHERE extname='vector'")) {
        String v = r.next() ? r.getString(1) : "MISSING";
        json.append("  \"pgvectorVersion\": \"").append(v).append("\",\n");
        System.out.println("PGVECTOR_VERSION=" + v);
    }
    // 两表 embedding 列类型
    for (String t : new String[]{"one_d38_vector_store", "d38_vector_store"}) {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a JOIN pg_class cl ON a.attrelid=cl.oid JOIN pg_namespace n ON cl.relnamespace=n.oid WHERE n.nspname='public' AND cl.relname=? AND a.attname='embedding'")) {
            p.setString(1, t);
            try (ResultSet r = p.executeQuery()) {
                String ft = r.next() ? r.getString(1) : "MISSING";
                json.append("  \"").append(t).append(".embedding\": \"").append(ft).append("\",\n");
                System.out.println(t + ".embedding=" + ft);
            }
        }
        // 索引
        try (PreparedStatement p = c.prepareStatement("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname='public' AND tablename=? ORDER BY indexname")) {
            p.setString(1, t);
            try (ResultSet r = p.executeQuery()) {
                StringBuilder idx = new StringBuilder("[");
                boolean first = true;
                while (r.next()) {
                    if (!first) idx.append(", ");
                    idx.append("{\"name\": \"").append(r.getString(1)).append("\", \"def\": \"").append(r.getString(2).replace("\"", "'")).append("\"}");
                    first = false;
                }
                idx.append("]");
                json.append("  \"").append(t).append(".indexes\": ").append(idx).append(",\n");
                System.out.println(t + ".indexes=" + idx);
            }
        }
        // 行数
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM public.\"" + t + "\"")) {
            r.next();
            long n = r.getLong(1);
            json.append("  \"").append(t).append(".rowCount\": ").append(n).append(",\n");
            System.out.println(t + ".rowCount=" + n);
        }
    }
    json.append("  \"verifiedAt\": \"").append(java.time.OffsetDateTime.now()).append("\"\n}");
}
Files.writeString(Path.of(outDir, "pg-catalog.json"), json.toString(), StandardCharsets.UTF_8);
System.out.println("PG_CATALOG_DONE");
/exit