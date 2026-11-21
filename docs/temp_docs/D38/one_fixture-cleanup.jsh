// one_fixture-cleanup.jsh — D38 one_ 夹具 MySQL 行清理与生命周期核实
// 仅操作本迭代夹具 documentId（D38-ONE-*）；语料文档只读查询，不触碰 local 轨与既有表结构。
Class.forName("com.mysql.cj.jdbc.Driver");
var c = java.sql.DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", System.getenv("MYSQL_PASSWORD") == null ? "123456" : System.getenv("MYSQL_PASSWORD"));
var st = c.createStatement();
var rs = st.executeQuery("SELECT document_id, lifecycle_status FROM knowledge_document WHERE document_id IN ('DOC-CNC-COOLING-001','DOC-SERVO-TROUBLE-002','DOC-WORKORDER-SPEC-003','D38-ONE-CONFLICT-901','D38-ONE-LIFECYCLE-902') ORDER BY document_id");
while (rs.next()) System.out.println("ROW|" + rs.getString(1) + "|" + rs.getString(2));
int n = st.executeUpdate("DELETE FROM knowledge_document WHERE document_id = 'D38-ONE-CONFLICT-901'");
System.out.println("FIXTURE_DELETED=" + n);
var rs2 = st.executeQuery("SELECT COUNT(*) FROM knowledge_document WHERE document_id LIKE 'D38-ONE-%'");
rs2.next();
System.out.println("FIXTURE_REMAINING=" + rs2.getInt(1));
var rs3 = st.executeQuery("SELECT COUNT(*) FROM one_document_vector_index");
rs3.next();
System.out.println("ONE_INDEX_ROWS=" + rs3.getInt(1));
var rs4 = st.executeQuery("SELECT COUNT(*) FROM one_document_chunk_index");
rs4.next();
System.out.println("ONE_CHUNK_ROWS=" + rs4.getInt(1));
// 两轨夹具残留核验（C18）：D37-EVAL-% 与 D38-ONE-% 均须为 0
var rs5 = st.executeQuery("SELECT COUNT(*) FROM knowledge_document WHERE document_id LIKE 'D37-EVAL-%' OR document_id LIKE 'D38-ONE-%'");
rs5.next();
System.out.println("BOTH_TRACK_FIXTURE_DOC_ROWS=" + rs5.getInt(1));
var rs6 = st.executeQuery("SELECT COUNT(*) FROM document_vector_index WHERE document_id LIKE 'D37-EVAL-%' OR document_id LIKE 'D38-ONE-%'");
rs6.next();
System.out.println("LOCAL_INDEX_FIXTURE_ROWS=" + rs6.getInt(1));
var rs7 = st.executeQuery("SELECT COUNT(*) FROM one_document_vector_index WHERE document_id LIKE 'D37-EVAL-%' OR document_id LIKE 'D38-ONE-%'");
rs7.next();
System.out.println("ONE_INDEX_FIXTURE_ROWS=" + rs7.getInt(1));
c.close();
/exit