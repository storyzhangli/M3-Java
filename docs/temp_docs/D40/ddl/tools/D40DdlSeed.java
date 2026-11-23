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
 * D40 P1 治理面 DDL + 凭据种子 harness（C03，幂等）：
 *  1) CREATE TABLE IF NOT EXISTS ×3（d40_document_sync / d40_admin_audit /
 *     d40_auth_principal），每表 DDL 执行 ×2 零报错 = 幂等判据；
 *  2) 4 个 principal（KP 冻结清单：adminA1/userA1/userB1/userC1），
 *     token 明文 d40tok- 前缀随机生成，仅写夹具 JSON（temp_docs，禁提交，H9）；
 *  3) 复跑复用既有夹具 token 重插（ON DUPLICATE KEY UPDATE），不产生新明文；
 *  4) 核验回读：三表存在 + principal count=4 + 哈希对拍。
 * 密码仅环境变量 MYSQL_PASSWORD；零落任何明文凭据。
 */
public final class D40DdlSeed {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String DDL_SYNC = """
            CREATE TABLE IF NOT EXISTS d40_document_sync (
              id            BIGINT        NOT NULL AUTO_INCREMENT,
              document_id   VARCHAR(64)   NOT NULL,
              operation     ENUM('DELETE','UPDATE_CLEANUP') NOT NULL,
              version       INT           NOT NULL DEFAULT 0 COMMENT '0=全版本(DELETE)',
              sync_status   ENUM('PENDING','SYNCED','FAILED') NOT NULL,
              reason_code   VARCHAR(64)   NOT NULL DEFAULT '',
              attempt_count INT           NOT NULL DEFAULT 0,
              next_retry_at DATETIME      NULL,
              payload_hash  CHAR(64)      NOT NULL DEFAULT '',
              created_at    DATETIME      NOT NULL,
              updated_at    DATETIME      NOT NULL,
              PRIMARY KEY (id),
              UNIQUE KEY uk_doc_op_version (document_id, operation, version),
              KEY idx_sync_status_retry (sync_status, next_retry_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
            """;

    private static final String DDL_AUDIT = """
            CREATE TABLE IF NOT EXISTS d40_admin_audit (
              id                 BIGINT       NOT NULL AUTO_INCREMENT,
              actor_user_id      VARCHAR(64)  NOT NULL,
              tenant_id          VARCHAR(64)  NOT NULL,
              role               VARCHAR(16)  NOT NULL,
              action             VARCHAR(64)  NOT NULL,
              target_document_id VARCHAR(64)  NOT NULL DEFAULT '',
              version            INT          NOT NULL DEFAULT 0,
              result             VARCHAR(16)  NOT NULL,
              reason_code        VARCHAR(64)  NOT NULL DEFAULT '',
              request_id         VARCHAR(64)  NOT NULL DEFAULT '',
              created_at         DATETIME     NOT NULL,
              PRIMARY KEY (id),
              KEY idx_audit_actor_time (actor_user_id, created_at),
              KEY idx_audit_action (action)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
            """;

    private static final String DDL_PRINCIPAL = """
            CREATE TABLE IF NOT EXISTS d40_auth_principal (
              token_sha256  CHAR(64)      NOT NULL,
              user_id       VARCHAR(64)   NOT NULL,
              tenant_id     VARCHAR(64)   NOT NULL,
              dept_id       VARCHAR(64)   NOT NULL,
              device_scopes JSON          NOT NULL,
              role          ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
              enabled       TINYINT(1)    NOT NULL DEFAULT 1,
              expires_at    DATETIME      NULL,
              created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (token_sha256),
              KEY idx_d40_principal_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
            """;

    private static final String UPSERT = """
            INSERT INTO d40_auth_principal
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

        // 1) 三表 DDL 各执行 ×2（C03：两次均零报错=幂等）
        try (Connection c = DriverManager.getConnection(url, "root", pass); Statement st = c.createStatement()) {
            for (String ddl : new String[]{DDL_SYNC, DDL_AUDIT, DDL_PRINCIPAL}) {
                st.execute(ddl);
                st.execute(ddl);
            }
        }
        System.out.println("[DDL] d40_document_sync / d40_admin_audit / d40_auth_principal created-if-absent, each x2 OK");

        // 2) 夹具 token 读取或生成（已存在复用，幂等重跑不产生新明文）
        Map<String, String> tokens = new LinkedHashMap<>();
        boolean generated = false;
        if (Files.exists(fixture)) {
            var root = M.readTree(Files.readString(fixture, StandardCharsets.UTF_8));
            root.path("principals").fields().forEachRemaining(e -> tokens.put(e.getKey(), e.getValue().path("token").asText()));
        } else {
            generated = true;
            SecureRandom rnd = new SecureRandom();
            for (String name : new String[]{"adminA1", "userA1", "userB1", "userC1"}) {
                byte[] buf = new byte[32];
                rnd.nextBytes(buf);
                tokens.put(name, "d40tok-" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf));
            }
        }

        // 3) 4 principal（KP 冻结：adminA1 ADMIN/T1/D-HQ/[]，其余 USER）
        record PrincipalDef(String user, String tenant, String dept, String[] devices, String role) {
        }
        Map<String, PrincipalDef> defs = new LinkedHashMap<>();
        defs.put("adminA1", new PrincipalDef("adminA1", "T1", "D-HQ", new String[]{}, "ADMIN"));
        defs.put("userA1", new PrincipalDef("userA1", "T1", "D1", new String[]{"DEV-A"}, "USER"));
        defs.put("userB1", new PrincipalDef("userB1", "T1", "D2", new String[]{"DEV-B"}, "USER"));
        defs.put("userC1", new PrincipalDef("userC1", "T2", "D1", new String[]{"DEV-A"}, "USER"));

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
                ps.setInt(7, 1);
                ps.setNull(8, Types.TIMESTAMP);
                ps.addBatch();
            }
            int[] applied = ps.executeBatch();
            System.out.println("[SEED] d40_auth_principal upsert rows=" + applied.length);
        }

        // 4) 夹具落盘（仅首次生成）
        if (generated) {
            Files.createDirectories(fixture.getParent());
            ObjectNode root = M.createObjectNode();
            root.put("note", "D40 夹具凭据明文——仅本地测试用，禁止提交 Git/日志/文档（H9）");
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

        // 5) 核验回读：三表存在 + principal 计数 + 角色分布 + 哈希对拍
        try (Connection c = DriverManager.getConnection(url, "root", pass)) {
            for (String table : new String[]{"d40_document_sync", "d40_admin_audit", "d40_auth_principal"}) {
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT count(*) FROM `" + table + "`")) {
                    rs.next();
                    System.out.println("[VERIFY] table=" + table + " rows=" + rs.getInt(1));
                }
            }
            int admins = 0, users = 0;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT role, count(*) n FROM d40_auth_principal GROUP BY role")) {
                while (rs.next()) {
                    if ("ADMIN".equals(rs.getString(1))) {
                        admins = rs.getInt(2);
                    } else {
                        users = rs.getInt(2);
                    }
                }
            }
            if (admins != 1 || users != 3) {
                throw new IllegalStateException("SEED_VERIFY_FAILED admin=" + admins + " user=" + users);
            }
            for (var e : tokens.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM d40_auth_principal WHERE token_sha256=?")) {
                    ps.setString(1, sha256Hex(e.getValue()));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) != 1) {
                            throw new IllegalStateException("HASH_VERIFY_FAILED:" + e.getKey());
                        }
                    }
                }
            }
            System.out.println("[VERIFY] principal 1 ADMIN + 3 USER, all token_sha256 hit");
        }
        System.out.println("[DONE] DDL_SEED_OK");
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
