import java.sql.*;
/** D44ActivateFix — KP2 seeding 显式 activate 回写（仅 3 语料文档；非业务代码，数据面终态恢复）。
 *  根因：KnowledgeDocumentMapper 只有 markDeprecated，无 DEPRECATED→ACTIVE 写回路径（D43"自愈"叙事更正）。
 *  用法: java D44ActivateFix <mysqlUrl> <user> <pass> <docId...> */
public class D44ActivateFix {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2])) {
      for (int i = 3; i < a.length; i++) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE knowledge_document SET lifecycle_status='ACTIVE', updated_at=NOW() WHERE document_id=? AND lifecycle_status<>'ACTIVE'")) {
          ps.setString(1, a[i]);
          System.out.println("ACTIVATE " + a[i] + " rows=" + ps.executeUpdate());
        }
      }
    }
  }
}
