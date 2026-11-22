# D39-R01 C01 Java 单测证据（#237）

- 执行时间：2026-11-22（本地）
- 命令：`mvn -B -f D39\D39_KAAMF\pom.xml test`（JDK21 / Maven 3.9.16 / 本地仓库 settings 生效）
- 结果：**Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**（真实执行并通过）

## 测试清单（本模块首个 Java 单测先例；纯 JVM 零 Spring 上下文零外部依赖）

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| access/PermissionScopeAlgebraTest | 7 | USER/ADMIN 全维真值表（tenant×dept×device 含 null/blank/通配）；H7 租户硬边界（含 chunk tenant='*' 无效）；D3 遗留无标签 fail-closed；空/多设备集合；matchesDocument 同构 |
| access/AccessPolicyServiceVectorPredicateTest | 5 | vectorPredicate AST 结构冻结（USER 前序=AND,AND,EQ,GROUP,OR,EQ,EQ,GROUP,IN；ADMIN=单 EQ）；谓词三键常量冻结；IN 值集=devices∪'*'；andWithAccess 组合（业务 null→纯权限；非 null→顶层 AND 左挂原对象） |
| access/AccessTokenHasherAndAuthContextTest | 5 | SHA-256 已知向量（abc/空串 FIPS 标准值）；UTF-8 中文幂等；AuthContext deviceScopes 防御拷贝+不可变；admin() 只认服务端 role；PermissionScope.of 透传等值 |
| hybrid/keyword/KeywordFilterCriteriaTest | 6 | 四参兼容构造=D38 行为（H1 零差异）；permission 非空→isEmpty=false；USER/ADMIN 逐 chunk 过滤；业务 AND 权限组合；四参 ChunkFields 无标签 fail-closed |
| hybrid/keyword/InvertedIndexChunkEntryTest | 2 | 13 参 ChunkEntry→filterFields 权限三字段透传；遗留行 null 容忍（不补造） |
| hybrid/fusion/RrfFusionClosureTest | 4 | **融合闭包**：输出 ⊆ 权限集/两路输入并集；权限外 chunk 不可凭空出现；k=60 数值冻结（1/61）；同分 chunkId 升序+topK 截断 |

## 过程中的实测修正（留痕）

1. Spring AI 1.1.8 `Filter.Expression` 为 **record**（访问器 `type()/left()/right()`，非公开字段；
   初次按字段访问编译失败 14 errors，javap 实测后改访问器）。
2. `FilterExpressionBuilder.group(op)` 产出 **Filter$Group** 节点（accessor `content()`），
   不是 Expression——初版递归只收集到 [AND,AND,EQ]，dump 真实 AST 后重写递归收集，
   前序序列冻结为 containsExactly 断言。
3. ExpressionType 常量名为大写（AND/OR/EQ/IN）。
4. surefire 由 pom 既有配置挂载 mockito-core javaagent + test profile（本批单测不依赖，未受影响）。
5. 测试源码零密钥；surefire-reports 原始目录不入 Git（.gitignore 既有规则）。

## 与集成层的分工

- 单测层（本文件）：代数真值表、AST 结构、不可变性、闭包纯函数——防双实现漂移（§3.1）。
- 集成层（#239 T1–T10）：真实 PG 渲染下推、HTTP 拒绝码、暴露面收口、fail-closed 缺失键行为——端到端证明。
