import java.sql.*;
public class QD44 { public static void main(String[] a) throws Exception {
  // 用法: java QD44 <url> <user> <pass> <sql>...  SELECT/SHOW 走行打印；其余走 executeUpdate 打印影响行数
  try (Connection c = DriverManager.getConnection(a[0],a[1],a[2]); Statement s = c.createStatement()) {
    for (int i = 3; i < a.length; i++) {
      System.out.println("### " + a[i]);
      String u = a[i].trim().toUpperCase();
      try {
        if (u.startsWith("SELECT") || u.startsWith("SHOW") || u.startsWith("DESC")) {
          try (ResultSet r = s.executeQuery(a[i])) {
            int n = r.getMetaData().getColumnCount();
            while (r.next()) { StringBuilder sb = new StringBuilder(); for (int j=1;j<=n;j++) sb.append(r.getString(j)).append(" | "); System.out.println(sb); }
          }
        } else { System.out.println("UPDATED=" + s.executeUpdate(a[i])); }
      } catch (SQLException e) { System.out.println("ERR " + e.getMessage()); }
    } } } }
