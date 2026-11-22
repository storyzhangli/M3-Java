import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D39 凭据种子 harness（P1，幂等）：
 *  1) CREATE TABLE IF NOT EXISTS d39_auth_principal（×2 重跑零报错=幂等，C03）
 *  2) 5 个 principal（任务书 P1.4 冻结）；token 明文随机生成，仅写夹具 JSON（temp_docs，不提交 Git，H9）
 *  3) 复跑语义：夹具已存在则复用原 token 重插（ON DUPLICATE KEY UPDATE），不产生新明文
 *  4) 核验回读：SELECT count 与哈希一致断言
 * 密码仅环境变量 MYSQL_PASSWORD。
 */
public final class D39AuthSeed {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS d39_auth_principal (
              token_sha256  CHAR(64)      NOT NULL COMMENT 'Bearer 令牌 SHA-256 小写 hex（DB 不存明文，H9）',
              user_id       VARCHAR(64)   NOT NULL COMMENT '业务用户（enabled 时兼会话 owner，D6）',
              tenant_id     VARCHAR(64)   NOT NULL COMMENT '租户（硬隔离边界，H7）',
              dept_id       VARCHAR(64)   NOT NULL COMMENT '部门（ADMIN 走 bypass 仍需显式值）',
              device_scopes JSON          NOT NULL COMMENT '可见设备数组；[]=无专属设备仅看 * 文档',
              role          ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '服务端角色（唯一权威，H3）',
              enabled       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '禁用位（0=fail-closed 拒绝）',
              expires_at    DATETIME      NULL COMMENT '过期时刻（NULL=不过期）',
              created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (token_sha256),
              KEY idx_principal_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
            """;

    private static final String UPSERT = """
            INSERT INTO d39_auth_principal
              (token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled, expires_at)
            VALUES (?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              user_id=VALUES(user_id), tenant_id=VALUES(tenant_id), dept_id=VALUES(dept_id),
              device_scopes=VALUES(device_scopes), role=VALUES(role),
              enabled=VALUES(enabled), expires_at=VALUES(expires_at)
            """;

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[0]);
        String url = "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        String pass = System.getenv("MYSQL_PASSWORD");
        if (pass == null || pass.isBlank()) {
            throw new IllegalStateException("ENV_MISSING:MYSQL_PASSWORD");
        }

        // 1) DDL 幂等 ×2（C03 判据：两次执行均零报错）
        int ddlRuns = 0;
        try (Connection c = DriverManager.getConnection(url, "root", pass); Statement st = c.createStatement()) {
            st.execute(DDL);
            st.execute(DDL);
            ddlRuns = 2;
        }
        System.out.println("[DDL] d39_auth_principal created-if-absent, runs=" + ddlRuns + " (idempotent x2 OK)");

        // 2) 读取/生成 token 夹具（已存在则复用，保证幂等重跑不产生新明文）
        Map<String, String> tokens = new LinkedHashMap<>();
        boolean generated = false;
        if (Files.exists(fixture)) {
            var root = M.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
            root.path("principals").fields().forEachRemaining(e -> tokens.put(e.getKey(), e.getValue().path("token").asText()));
        } else {
            generated = true;
            SecureRandom rnd = new SecureRandom();
            for (String name : new String[]{"userA", "userB", "adminT1", "userC", "expiredUser"}) {
                byte[] buf = new byte[32];
                rnd.nextBytes(buf);
                tokens.put(name, "d39tok-" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf));
            }
        }

        // 3) 五个 principal 定义（P1.4 冻结；expiredUser 过期=2020-01-01）
        record PrincipalDef(String user, String tenant, String dept, String[] devices, String role, int enabled, String expires) {
        }
        Map<String, PrincipalDef> defs = new LinkedHashMap<>();
        defs.put("userA", new PrincipalDef("userA", "T1", "D1", new String[]{"DEV-A"}, "USER", 1, null));
        defs.put("userB", new PrincipalDef("userB", "T1", "D2", new String[]{"DEV-B"}, "USER", 1, null));
        defs.put("adminT1", new PrincipalDef("adminT1", "T1", "D-HQ", new String[]{}, "ADMIN", 1, null));
        defs.put("userC", new PrincipalDef("userC", "T2", "D1", new String[]{"DEV-A"}, "USER", 1, null));
        defs.put("expiredUser", new PrincipalDef("expiredUser", "T1", "D2", new String[]{"DEV-B"}, "USER", 1, "2020-01-01 00:00:00"));

        try (Connection c = DriverManager.getConnection(url, "root", pass);
             PreparedStatement ps = c.prepareStatement(UPSERT)) {
            for (var e : defs.entrySet()) {
                PrincipalDef d = e.getValue();
                ps.setString(1, sha256Hex(tokens.get(e.getKey())));
                ps.setString(2, d.user());
                ps.setString(3, d.tenant());
                ps.setString(4, d.dept());
                ArrayNode arr = M.createArrayNode();
                for (String dev : d.devices()) {
                    arr.add(dev);
                }
                ps.setString(5, M.writeValueAsString(arr));
                ps.setString(6, d.role());
                ps.setInt(7, d.enabled());
                if (d.expires() == null) {
                    ps.setNull(8, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(8, Timestamp.valueOf(d.expires()));
                }
                ps.addBatch();
            }
            int[] applied = ps.executeBatch();
            System.out.println("[SEED] upsert rows=" + applied.length + " (INSERT..ON DUPLICATE KEY UPDATE)");
        }

        // 4) 夹具落盘（首次生成时；权限 600 语义在 NTFS 不适用，目录本身 git-ignored，H9 不提交）
        if (generated) {
            Files.createDirectories(fixture.getParent());
            ObjectNode root = M.createObjectNode();
            root.put("note", "D39 夹具凭据明文——仅本地测试用，禁止提交 Git/日志/文档（H9）");
            root.put("generatedAt", Timestamp.valueOf(java.time.LocalDateTime.now()).toString());
            ObjectNode principals = root.putObject("principals");
            for (var e : tokens.entrySet()) {
                ObjectNode one = principals.putObject(e.getKey());
                one.put("token", e.getValue());
                one.put("tokenSha256", sha256Hex(e.getValue()));
            }
            Files.writeString(fixture, M.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            System.out.println("[FIXTURE] tokens written (plaintext once): " + fixture);
        } else {
            System.out.println("[FIXTURE] reuse existing token file (no new plaintext)");
        }

        // 5) 核验回读（只断言哈希与计数，不回显任何 token）
        try (Connection c = DriverManager.getConnection(url, "root", pass);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT user_id, tenant_id, dept_id, role, enabled, (expires_at IS NOT NULL) has_expiry FROM d39_auth_principal ORDER BY user_id")) {
            int n = 0;
            StringBuilder sb = new StringBuilder("[VERIFY] ");
            while (rs.next()) {
                n++;
                sb.append(rs.getString(1)).append('(').append(rs.getString(2)).append('/').append(rs.getString(3))
                        .append('/').append(rs.getString(4)).append(rs.getString(5).equals("1") ? "/on" : "/off")
                        .append(rs.getBoolean(6) ? "/exp" : "").append(") ");
            }
            sb.append("count=").append(n);
            System.out.println(sb);
            if (n != 5) {
                throw new IllegalStateException("SEED_VERIFY_FAILED count=" + n);
            }
        }
        // 6) 哈希一致性断言（运行时同款 SHA-256 算法对拍）
        try (Connection c = DriverManager.getConnection(url, "root", pass)) {
            for (var e : tokens.entrySet()) {
                String hash = sha256Hex(e.getValue());
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM d39_auth_principal WHERE token_sha256=?")) {
                    ps.setString(1, hash);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) != 1) {
                            throw new IllegalStateException("HASH_VERIFY_FAILED:" + e.getKey());
                        }
                    }
                }
            }
            System.out.println("[VERIFY] all 5 token_sha256 hit by runtime-algorithm hash");
        }
        System.out.println("[DONE] SEED_OK");
    }

    private static String sha256Hex(String token) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : md.digest(token.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
