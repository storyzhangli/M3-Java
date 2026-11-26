import java.sql.*;

/** D43QIA — d43_admin_audit 快照：无 a[3]=仅 count/max；带 minId=count/max + dump id>minId 行（C15 差值）。 */
public class D43QIA {
    public static void main(String[] a) throws Exception {
        try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement s = c.createStatement()) {
            ResultSet r = s.executeQuery("SELECT COUNT(*), COALESCE(MAX(id),0) FROM d43_admin_audit");
            r.next();
            long cnt = r.getLong(1);
            long max = r.getLong(2);
            System.out.println("AUDIT count=" + cnt + " maxId=" + max);
            if (a.length > 3) {
                long min = Long.parseLong(a[3]);
                ResultSet d = s.executeQuery("SELECT actor_user_id,tenant_id,role,action,target_document_id,result,reason_code,created_at"
                        + " FROM d43_admin_audit WHERE id > " + min + " ORDER BY id");
                while (d.next()) {
                    System.out.println("DELTA | " + d.getString(1) + " | " + d.getString(2) + " | " + d.getString(3)
                            + " | " + d.getString(4) + " | " + d.getString(5) + " | " + d.getString(6)
                            + " | " + d.getString(7) + " | " + d.getString(8));
                }
            }
        }
    }
}
