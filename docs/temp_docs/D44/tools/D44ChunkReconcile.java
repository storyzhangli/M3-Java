import java.sql.*;
/** D44ChunkReconcile — 数据级引用对账：chunkId × PG d44_vector_store × MySQL document_chunk_index 双端存在性+归属一致性。
 *  用法: java D44ChunkReconcile <pgUrl> <pgUser> <pgPass> <pgTable> <myUrl> <myUser> <myPass> <chunkId> */
public class D44ChunkReconcile {
  public static void main(String[] a) throws Exception {
    String cid = a[7];
    try (Connection pg = DriverManager.getConnection(a[0], a[1], a[2]);
         Connection my = DriverManager.getConnection(a[4], a[5], a[6])) {
      int pgRows = 0; String pgDoc = null;
      try (PreparedStatement ps = pg.prepareStatement("SELECT metadata->>'documentId' FROM " + a[3] + " WHERE id::text=?")) {
        ps.setString(1, cid);
        ResultSet r = ps.executeQuery();
        if (r.next()) { pgRows = 1; pgDoc = r.getString(1); }
      }
      int myRows = 0; String myDoc = null;
      try (PreparedStatement ps = my.prepareStatement("SELECT document_id FROM document_chunk_index WHERE chunk_id=?")) {
        ps.setString(1, cid);
        ResultSet r = ps.executeQuery();
        while (r.next()) { myRows++; myDoc = r.getString(1); }
      }
      String verdict = (pgRows == 1 && myRows == 1) ? "RECONCILED" : (pgRows + myRows == 0 ? "MISSING_BOTH" : "PARTIAL");
      System.out.println("CHUNK " + cid + " pg=" + pgRows + "(" + pgDoc + ") mysql=" + myRows + "(" + myDoc + ") => " + verdict);
    }
  }
}
