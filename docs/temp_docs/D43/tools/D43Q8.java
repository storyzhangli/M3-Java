import java.sql.*;
public class D43Q8 {
  public static void main(String[] a) throws Exception {
    try (Connection c = DriverManager.getConnection(a[0], a[1], a[2]); Statement st = c.createStatement()) {
      System.out.println("== TABLE EXTRACT (d43) ==");
      ResultSet rs = st.executeQuery("SELECT id, document_id, page_number, table_index, row_count, col_count, continuation_of_table_id, status FROM d43_table_extract WHERE document_id LIKE 'D43E2E%' ORDER BY document_id, page_number, table_index");
      while (rs.next()) System.out.println("T id=" + rs.getLong(1) + " doc=" + rs.getString(2) + " p" + rs.getInt(3) + " ti=" + rs.getInt(4) + " rows=" + rs.getInt(5) + " cols=" + rs.getInt(6) + " cont=" + rs.getString(7) + " " + rs.getString(8));
      System.out.println("== ROWS (cellsJson per table) ==");
      rs = st.executeQuery("SELECT r.table_id, e.document_id, e.page_number, r.row_index, r.cells_json FROM d43_table_row r JOIN d43_table_extract e ON r.table_id=e.id WHERE e.document_id LIKE 'D43E2E%' ORDER BY e.document_id, e.page_number, r.row_index");
      while (rs.next()) System.out.println("R " + rs.getString(2) + " p" + rs.getInt(3) + " ri" + rs.getInt(4) + " " + rs.getString(5));
      System.out.println("== PG rows for PIPE-001 ==");
    }
  }
}
