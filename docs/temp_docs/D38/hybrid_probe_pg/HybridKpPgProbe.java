import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * D38-R02 P0 探测（KP1 PG 中文分词能力 + KP2 倒排语料一致性）。
 * 全只读：零 DDL、零写入、只 SELECT；密码仅经环境变量 PG_VECTOR_PASSWORD 注入，禁止回显。
 * 取代 jshell 通道（本环境 jshell 启动脚本不稳定），用 javac/java 确定性执行。
 */
public final class HybridKpPgProbe {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://192.168.200.153:5432/spring_ai";
        String pwd = System.getenv("PG_VECTOR_PASSWORD");
        if (pwd == null || pwd.isBlank()) {
            System.out.println("FATAL=PG_VECTOR_PASSWORD_MISSING");
            System.exit(2);
        }
        try (Connection c = DriverManager.getConnection(url, "postgres", pwd)) {
            c.setReadOnly(true);
            try (Statement st = c.createStatement()) {
                ResultSet rs = st.executeQuery("SHOW server_version");
                rs.next();
                System.out.println("KP1_SERVER_VERSION=" + rs.getString(1));

                rs = st.executeQuery("SELECT extname FROM pg_extension ORDER BY extname");
                StringBuilder ext = new StringBuilder();
                while (rs.next()) {
                    ext.append(rs.getString(1)).append(",");
                }
                System.out.println("KP1_INSTALLED_EXTENSIONS=" + ext);

                rs = st.executeQuery("SELECT name, default_version, installed_version "
                        + "FROM pg_available_extensions "
                        + "WHERE name IN ('zhparser','pg_jieba','pg_trgm','vector') ORDER BY name");
                while (rs.next()) {
                    System.out.println("KP1_AVAIL=" + rs.getString(1) + "|defVer=" + rs.getString(2)
                            + "|installedVer=" + rs.getString(3));
                }

                rs = st.executeQuery("SELECT to_tsvector('simple','数控机床主轴过热报警')::text");
                rs.next();
                System.out.println("KP1_TSV_SIMPLE=" + rs.getString(1));

                rs = st.executeQuery("SELECT count(*) FROM pg_ts_config WHERE cfgname "
                        + "IN ('zhparser','zh_cn','chinese','jiebacfg')");
                rs.next();
                System.out.println("KP1_CN_TS_CONFIG_COUNT=" + rs.getString(1));

                rs = st.executeQuery("SELECT count(*) FROM public.d38_vector_store");
                rs.next();
                System.out.println("KP2_ROW_COUNT=" + rs.getString(1));

                MessageDigest md = MessageDigest.getInstance("SHA-256");
                rs = st.executeQuery("SELECT id, content, metadata::text FROM public.d38_vector_store ORDER BY id::text");
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    String id = rs.getString(1);
                    String content = rs.getString(2);
                    String meta = rs.getString(3);
                    byte[] h = md.digest(content.getBytes("UTF-8"));
                    StringBuilder hex = new StringBuilder();
                    for (byte b : h) {
                        hex.append(String.format("%02x", b));
                    }
                    lines.add(id + "|" + hex + "|" + content.length() + "|" + meta);
                }
                Collections.sort(lines);
                System.out.println("KP2_CHUNKS_BEGIN");
                for (String l : lines) {
                    System.out.println("KP2_CHUNK=" + l);
                }
                System.out.println("KP2_CHUNKS_END");

                byte[] fh = md.digest(String.join("\n", lines).getBytes("UTF-8"));
                StringBuilder fhex = new StringBuilder();
                for (byte b : fh) {
                    fhex.append(String.format("%02x", b));
                }
                System.out.println("KP2_COLLECTION_FINGERPRINT=" + fhex);
            }
        }
        System.out.println("KP_DONE=OK");
    }
}
