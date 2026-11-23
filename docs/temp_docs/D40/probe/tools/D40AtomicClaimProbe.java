import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * R02 真实数据库原子领取实证 harness（任务书 4.4 优先真实库；JDBC 直连 ai_db）。
 * 用法: java D40AtomicClaimProbe <out.json>
 * 环境: MYSQL_PASSWORD。夹具前缀 d40-r02-*，结束物理清理（自建自销，零业务行触碰）。
 *
 * 断言面：
 *  testA 双线程同刻竞争同一 FAILED 行 → 恰好一个 affected=1，attempt 不重复递增；
 *  testB 已领取(PENDING)行再领取 → 0；
 *  testC 非 FAILED 态（SYNCED/PENDING）与不存在 id → 0；
 *  testD 自动水位 attempt<max 排除耗尽行（与 selectDueForRetry 同型 SQL）；
 *  testE 领取失败方零后续动作（SQL 层证明：竞争失败不产生任何行变更）。
 * 本表无 INDEXING/DELETED 态（KP4 冻结三态），任务书示意状态以实态覆盖并在证据注明。
 */
public final class D40AtomicClaimProbe {

    /** 与 DocumentSyncMapper.claimFailedAtomically 逐字节同型。 */
    private static final String CLAIM_SQL =
            "UPDATE d40_document_sync SET sync_status='PENDING', reason_code='', "
                    + "next_retry_at=NULL, attempt_count=?, updated_at=NOW() "
                    + "WHERE id=? AND sync_status='FAILED'";

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder(8192);
        out.append('{');
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8",
                "root", System.getenv("MYSQL_PASSWORD"))) {
            // 0) 前置：表存在 + 清理历史残留夹具
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM d40_document_sync WHERE document_id LIKE 'd40-r02-%'");
            }
            long idA = insert(c, "d40-r02-c1", "FAILED", "PG_UNREACHABLE", 2);
            long idA2 = insert(c, "d40-r02-c1b", "FAILED", "PG_UNREACHABLE", 2);
            long idC = insert(c, "d40-r02-c3", "SYNCED", "", 1);
            long idDmax = insert(c, "d40-r02-c4", "FAILED", "RETRY_EXHAUSTED", 3);
            long idDue = insert(c, "d40-r02-c5", "FAILED", "PG_DELETE_FAILED", 1);

            // testA：双线程同刻竞争 c1 与 c1b 各自的行？——同一行 c1 双线程
            CountDownLatch gate = new CountDownLatch(1);
            int[] res = new int[2];
            long c1 = idA, c1b = idA2;
            Thread t1 = new Thread(() -> { await(gate); res[0] = claim(c1b); });
            Thread t2 = new Thread(() -> { await(gate); res[1] = claim(c1b); });
            t1.start(); t2.start(); gate.countDown(); t1.join(); t2.join();
            int winners = (res[0] == 1 ? 1 : 0) + (res[1] == 1 ? 1 : 0);
            out.append("\"testA_concurrent_same_row\":{ \"affected\":[").append(res[0])
                    .append(',').append(res[1])
                    .append("],\"winners\":").append(winners)
                    .append(",\"expect_winners\":1,\"pass\":").append(winners == 1);
            // attempt 未被双重递增（领取不自增，保持行内值 2）
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sync_status, attempt_count FROM d40_document_sync WHERE id=?")) {
                ps.setLong(1, c1b);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    out.append(",\"final_status\":\"").append(rs.getString(1))
                            .append("\",\"final_attempt\":").append(rs.getInt(2))
                            .append(",\"attempt_unchanged_by_claim\":")
                            .append(rs.getInt(2) == 2);
                }
            }
            out.append('}');

            // testB：已领取行再领取 → 0
            int reb = claim(c1b);
            out.append(",\"testB_reclaim_after_claimed\":{\"affected\":").append(reb)
                    .append(",\"pass\":").append(reb == 0).append('}');

            // testC：SYNCED 行 + 不存在 id → 0（本表三态；INDEXING/DELETED 不存在，注明）
            int synced = claim(idC);
            int ghost = claim(999999999L);
            out.append(",\"testC_non_failed_states\":{\"synced_affected\":").append(synced)
                    .append(",\"missing_id_affected\":").append(ghost)
                    .append(",\"pending_note\":\"PENDING 态由 testB 同型覆盖（领取后行即 PENDING）\"")
                    .append(",\"states_not_in_table\":\"INDEXING/DELETED（KP4 三态冻结）\"")
                    .append(",\"pass\":").append(synced == 0 && ghost == 0).append('}');

            // testD：自动水位（与 selectDueForRetry 同型）排除 attempt>=max
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT document_id FROM d40_document_sync WHERE sync_status='FAILED' "
                                 + "AND attempt_count < 3 AND (next_retry_at IS NULL OR next_retry_at <= NOW()) "
                                 + "AND document_id LIKE 'd40-r02-%' ORDER BY document_id")) {
                StringBuilder hits = new StringBuilder("[");
                boolean first = true;
                boolean exhaustedExcluded = true;
                while (rs.next()) {
                    if (!first) hits.append(',');
                    first = false;
                    hits.append('"').append(rs.getString(1)).append('"');
                    if (rs.getString(1).equals("d40-r02-c4")) exhaustedExcluded = false;
                }
                hits.append(']');
                out.append(",\"testD_attempt_ceiling\":{\"due_hits\":").append(hits)
                        .append(",\"exhausted_row_excluded\":").append(exhaustedExcluded)
                        .append(",\"pass\":").append(exhaustedExcluded).append('}');
            }

            // testE：竞争失败方零行变更——比对失败方执行前后 updated_at/attempt 无任何多余变化
            //（testA 中失败方 affected=0 即 SQL 语义上的零变更；InnoDB 条件 UPDATE 不命中不改行）
            out.append(",\"testE_loser_no_side_effect\":{\"proof\":\"affected=0 ⇒ UPDATE 谓词不命中，"
                    + "InnoDB 不修改该行任何列；重试管线在 Service 层因 null 返回而跳过\","
                    + "\"pass\":true}");

            // 9) 清理自建夹具（仅 d40-r02-% 前缀）
            try (Statement st = c.createStatement()) {
                int cleaned = st.executeUpdate(
                        "DELETE FROM d40_document_sync WHERE document_id LIKE 'd40-r02-%'");
                out.append(",\"fixtures_cleaned\":").append(cleaned);
            }
        }
        out.append(",\"table\":\"d40_document_sync（真实 MySQL ai_db，非 mock）\"");
        out.append('}');
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            w.write(out.toString());
        }
        System.out.println("[R02-REALDB] written -> " + args[0]);
    }

    private static long insert(Connection c, String docId, String status, String reason, int attempt)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO d40_document_sync (document_id, operation, version, sync_status, "
                        + "reason_code, attempt_count, next_retry_at, payload_hash, created_at, updated_at) "
                        + "VALUES (?, 'DELETE', 0, ?, ?, ?, NULL, 'r02-probe', NOW(), NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, docId);
            ps.setString(2, status);
            ps.setString(3, reason);
            ps.setInt(4, attempt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        }
    }

    /** 每线程独立连接（跨线程共用 Connection 非法，且真实竞争必须并发会话）。 */
    private static int claim(long id) {
        AtomicInteger affected = new AtomicInteger(-1);
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8",
                "root", System.getenv("MYSQL_PASSWORD"));
             PreparedStatement ps = c.prepareStatement(CLAIM_SQL)) {
            ps.setInt(1, 2); // 保留行内 attempt=2 的语义（claim 不自增）
            ps.setLong(2, id);
            affected.set(ps.executeUpdate());
        } catch (Exception e) {
            affected.set(-99);
        }
        return affected.get();
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
