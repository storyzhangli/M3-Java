import java.sql.*;
/** D44ActivateCheck — KP2 activate 核实：MySQL knowledge_document 三文档 lifecycle_status 直查 + d44_document_sync PENDING 计数。
 *  用法: java D44ActivateCheck <mysqlUrl> <user> <pass> <docId1> <docId2> ... */
public class D44ActivateCheck {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2])) {
      boolean allOk = true;
      for (int i = 3; i < a.length; i++) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT lifecycle_status, status, version FROM knowledge_document WHERE document_id=?")) {
          ps.setString(1, a[i]);
          ResultSet r = ps.executeQuery();
          if (r.next()) {
            String lc = r.getString(1);
            System.out.println("DOC " + a[i] + " lifecycle=" + lc + " status=" + r.getString(2) + " version=" + r.getInt(3));
            if (!"ACTIVE".equals(lc)) allOk = false;
          } else { System.out.println("DOC " + a[i] + " MISSING"); allOk = false; }
        }
      }
      try (Statement s = c.createStatement();
           ResultSet r = s.executeQuery("SELECT COUNT(*) FROM d44_document_sync WHERE sync_status='PENDING'")) {
        r.next();
        System.out.println("SYNC_PENDING=" + r.getInt(1) + (r.getInt(1) > 0 ? " (tombstone 窗口未收敛)" : ""));
        if (r.getInt(1) > 0) allOk = false;
      }
      System.out.println("ACTIVATE_ALL_OK=" + allOk);
      if (!allOk) System.exit(2);
    }
  }
}
