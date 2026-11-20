// D37 夹具清理与生命周期核实（仅操作本迭代夹具 documentId；语料文档只读查询）
Class.forName("com.mysql.cj.jdbc.Driver");
var c = java.sql.DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", "root", System.getenv("MYSQL_PASSWORD") == null ? "123456" : System.getenv("MYSQL_PASSWORD"));
var st = c.createStatement();
var rs = st.executeQuery("SELECT document_id, lifecycle_status FROM knowledge_document WHERE document_id IN ('DOC-CNC-COOLING-001','DOC-SERVO-TROUBLE-002','DOC-WORKORDER-SPEC-003','D37-EVAL-CONFLICT-901','D37-EVAL-LIFECYCLE-902') ORDER BY document_id");
while (rs.next()) System.out.println("ROW|" + rs.getString(1) + "|" + rs.getString(2));
int n = st.executeUpdate("DELETE FROM knowledge_document WHERE document_id = 'D37-EVAL-CONFLICT-901'");
System.out.println("FIXTURE_DELETED=" + n);
var rs2 = st.executeQuery("SELECT COUNT(*) FROM knowledge_document WHERE document_id LIKE 'D37-EVAL-%'");
rs2.next();
System.out.println("FIXTURE_REMAINING=" + rs2.getInt(1));
c.close();
/exit
