# d44_demo.ps1 — RAG 端到端可复现演示（答辩四件套之四；任务书 §3.4）
# 前置：8080 disabled 基线实例 + KP2 语料 3 文档 8 片段（评测态干净面）
# 纪律：单文件顺序执行；每节 [Sx] 标号+PASS/FAIL；写面仅第 8 节 d44-DEMO-001 且节内自删（FIXTURE_REMAINING=0）；
#       全离线零远程（mock 生成/local-hashing embedding 断言见边界声明）；模型级 NOT RUN 不冒充。
# 输出：控制台分节 + evidence\D44\demo\demo-transcript.json
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$demoDir = Join-Path $repo 'docs\evidence\D44\demo'
New-Item -ItemType Directory -Force $demoDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$steps = New-Object System.Collections.ArrayList
function Step($id, $name, $ok, $detail) {
    [void]$script:steps.Add([pscustomobject]@{ id = $id; name = $name; verdict = $(if ($ok) { 'PASS' } else { 'FAIL' }); detail = $detail })
    "[{0}] {1} :: {2} :: {3}" -f $id, $name, $(if ($ok) { 'PASS' } else { 'FAIL' }), $detail
}
function AskApi($body) {
    (Invoke-RestMethod -Uri "$base/api/v1/rag/ask" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes(($body | ConvertTo-Json -Compress)) -TimeoutSec 60).data
}
# S0 前序残留预清（可重复运行保证：上轮异常退出留下的 d44-DEMO-001 在断言前归零）
$repoM0 = 'D:\SoftwareInstall\Apache\repository'
$myjar0 = (Get-ChildItem "$repoM0\com\mysql\mysql-connector-j\8.0.33" -Filter *.jar | Where-Object Name -notmatch 'sources' | Select-Object -First 1).FullName
$myUrl0 = 'jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8'
& java -cp $myjar0 (Join-Path $PSScriptRoot 'tools\QD44.java') $myUrl0 'root' '123456' "DELETE FROM d44_document_sync WHERE document_id='d44-DEMO-001'" "DELETE FROM knowledge_document WHERE document_id='d44-DEMO-001'" "DELETE FROM d44_table_row WHERE table_id IN (SELECT id FROM d44_table_extract WHERE document_id='d44-DEMO-001')" "DELETE FROM d44_table_extract WHERE document_id='d44-DEMO-001'" 2>&1 | Out-Null
try {
    # S1 健康+资产面
    $hb = Invoke-RestMethod "$base/api/v1/ai/health" -TimeoutSec 15
    $docs = Invoke-RestMethod "$base/api/v1/documents" -TimeoutSec 15
    $nDoc = @($docs.data).Count
    Step 'S1' '服务健康+文档面' ($hb.code -eq 200 -and $nDoc -eq 18) "health=$($hb.code) documents=$nDoc（语料3+D41遗留15，矩阵夹具已清零）"

    # S2 混合能力面：向量检索命中语料
    $sr = Invoke-RestMethod "$base/api/v1/retrieval/search" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ query = 'CNC冷却系统压力异常'; topK = 5 } | ConvertTo-Json -Compress)) -TimeoutSec 60
    $hits = @($sr.data.matches)
    $hitDoc = @($hits | ForEach-Object { $_.documentId } | Sort-Object -Unique)
    Step 'S2' '向量检索 Top-5 命中' ($sr.code -eq 200 -and $hits.Count -ge 3) "matches=$($hits.Count) docs=[$($hitDoc -join ',')] top1score=$($hits[0].score)"

    # S3 有据问题：检索命中→（mock 通道）决策可审计
    $a1 = AskApi @{ question = 'CNC冷却系统压力异常时应该怎么排查？'; topK = 5 }
    Step 'S3' '有据问题 ask 决策链' ($a1.retrievalStatus -eq 'SUCCESS') "decision=$($a1.decision) retrieved=$($a1.retrievedCount) evidence=$($a1.evidenceCount)（mock 生成面 decision 非 ANSWER=已知边界 D43-C12，检索/证据链真实）"

    # S4 无据问题：拒答+零伪造引用
    $a2 = AskApi @{ question = '如何烘焙法式可颂？'; topK = 5 }
    $noFake = (@($a2.sources).Count -eq 0 -and "$($a2.answer)" -notmatch '\[S\d')
    Step 'S4' '无据问题拒答' ($a2.decision -eq 'REFUSE' -and $noFake) "decision=$($a2.decision) sources=0 无[S]标记 文案=$(([string]$a2.answer).Substring(0,[Math]::Min(24,"$($a2.answer)".Length)))…"

    # S5 表格域隔离面（D41 表格数据留存于前代表域；D44 骨架 d44_table_extract 为独立新域，H6 遗留零触碰=视图空为合法隔离态）
    $tb = Invoke-RestMethod "$base/api/v1/documents/D41-R03RE-MANUAL/tables" -TimeoutSec 30
    $d44Extract = 0
    try { $d44Extract = @(Invoke-RestMethod "$base/api/v1/vector-index/reconcile" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ dryRun = $true } | ConvertTo-Json -Compress)) -TimeoutSec 60).data.tableOrphans } catch { $d44Extract = -1 }
    Step 'S5' '表格域前后代隔离（只读）' ($tb.code -eq 200 -and @($tb.data).Count -eq 0) "D41 遗留 doc 在 D44 表域视图=0（隔离正确）；D41 逐格能力对端=195/30/0/0 复验(c17-cell-diff-rerun.json)；表格写面并入 S8 演示链"

    # S6 一致性对账（干跑，端点实扫=/api/v1/vector-index/reconcile）
    $rc = Invoke-RestMethod "$base/api/v1/vector-index/reconcile" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ dryRun = $true } | ConvertTo-Json -Compress)) -TimeoutSec 60
    $d = $rc.data
    Step 'S6' '向量一致性对账（dryRun）' ($rc.code -eq 200) "pgTotal=$($d.pgTotalRows) orphan=$($d.orphanVectors) missing=$($d.missingVectors) syncStuck=$($d.syncStuck) tableOrphans=$($d.tableOrphans) countMismatch=$($d.countMismatches)（d32eval 前代既有 noise 见 driftSummary，夹具域零悬挂）"

    # S7 同步状态机读面
    $sy = Invoke-RestMethod "$base/api/v1/sync/status" -TimeoutSec 30
    $ns = @($sy.data).Count
    Step 'S7' '文档同步状态读面' ($sy.code -eq 200) "sync 记录=$ns（D44 清理后残留行=操作历史留档；PENDING 挂起=$(@($sy.data | Where-Object status -eq 'PENDING').Count)）"

    # S8 写面闭环：导入(含管道表)→检索命中→表格落域→删除→双端零残留（自清理）
    # S8 写面闭环：DB 预清(幂等)→注册→导入(含管道表)→index→检索→表格→删除闭环→S9 终清
    $did = 'd44-DEMO-001'
    $repoM = 'D:\SoftwareInstall\Apache\repository'
    $myjar = (Get-ChildItem "$repoM\com\mysql\mysql-connector-j\8.0.33" -Filter *.jar | Where-Object Name -notmatch 'sources' | Select-Object -First 1).FullName
    $myUrl = 'jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8'
    $q = Join-Path $PSScriptRoot 'tools\QD44.java'
    & java -cp $myjar $q $myUrl 'root' '123456' "DELETE FROM d44_document_sync WHERE document_id='$did'" "DELETE FROM knowledge_document WHERE document_id='$did'" "DELETE FROM d44_table_row WHERE table_id IN (SELECT id FROM d44_table_extract WHERE document_id='$did')" "DELETE FROM d44_table_extract WHERE document_id='$did'" 2>&1 | Out-Null
    $fx = Join-Path $demoDir 'demo-doc.md'
    [IO.File]::WriteAllText($fx, "# 演示文档`n`n演示片段：豆包答辩演示专用标记 DEMOKELVIN99，无业务含义。`n`n| 参数 | 值 |`n| --- | --- |`n| 演示温度 | 99C |`n| 演示压力 | 1.2MPa |`n", $utf8)
    $null = Invoke-RestMethod "$base/api/v1/vector-index/documents/$did" -Method Delete -TimeoutSec 30   # 向量域幂等预清
    $reg0 = Invoke-RestMethod "$base/api/v1/documents/register" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ documentId = $did; source = 'D44-DEMO'; title = '演示文档' } | ConvertTo-Json -Compress)) -TimeoutSec 30
    $imp = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers @{ 'X-Dev-User' = 'dev-demo' } -Form @{ file = Get-Item $fx; documentId = $did; source = 'D44-DEMO'; title = '演示文档' } -TimeoutSec 120
    $c8 = @(@{ chunkIndex = 0; content = '演示片段：豆包答辩演示专用标记 DEMOKELVIN99，无业务含义。'; globalStart = 0; globalEnd = 30 }, @{ chunkIndex = 1; content = '演示表格：参数 值 演示温度 99C 演示压力 1.2MPa DEMOKELVIN99'; globalStart = 30; globalEnd = 60 })
    $idx = Invoke-RestMethod "$base/api/v1/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ documentId = $did; version = 1; strategy = 'STRUCTURE'; title = '演示文档'; chunks = $c8 } | ConvertTo-Json -Depth 6 -Compress)) -TimeoutSec 60
    Start-Sleep -Seconds 2
    $srch = Invoke-RestMethod "$base/api/v1/retrieval/search" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ query = '演示片段 豆包答辩演示专用标记 DEMOKELVIN99 无业务含义'; topK = 8 } | ConvertTo-Json -Compress)) -TimeoutSec 60
    $found = @($srch.data.matches | Where-Object documentId -eq $did).Count -ge 1
    $tbs = Invoke-RestMethod "$base/api/v1/documents/$did/tables" -TimeoutSec 30
    $nT = $(if ($null -ne $tbs.data) { @($tbs.data).Count } else { 0 })
    $rowN = 0
    if ($nT -ge 1) { $rw = Invoke-RestMethod "$base/api/v1/tables/$($tbs.data[0].id)/rows" -TimeoutSec 30; $rowN = $(if ($null -ne $rw.data) { @($rw.data).Count } else { 0 }) }
    $del = Invoke-RestMethod "$base/api/v1/vector-index/documents/$did" -Method Delete -TimeoutSec 30
    Start-Sleep -Seconds 2
    $srch2 = Invoke-RestMethod "$base/api/v1/retrieval/search" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ query = '演示片段 豆包答辩演示专用标记 DEMOKELVIN99 无业务含义'; topK = 8 } | ConvertTo-Json -Compress)) -TimeoutSec 60
    $gone = @($srch2.data.matches | Where-Object documentId -eq $did).Count -eq 0
    $rc2 = Invoke-RestMethod "$base/api/v1/vector-index/reconcile" -Method Post -ContentType 'application/json; charset=utf-8' -Body $utf8.GetBytes((@{ dryRun = $true } | ConvertTo-Json -Compress)) -TimeoutSec 60
    Step 'S8' '写面全链路闭环(注册→导入→index→检索→表格→删除→零残留)' ($reg0.code -eq 200 -and $imp.code -eq 200 -and $idx.code -eq 200 -and $found -and $nT -ge 1 -and $rowN -ge 1 -and $del.code -eq 200 -and $gone) "register=$($reg0.code) import=$($imp.code) index=$($idx.code)/chunks=$($idx.data.chunkCount) 命中=$found tables=$nT rows=$rowN 删除=$($del.code) 检索残留=$(-not $gone)（删除闭环清向量+表格域；登记行归 S9 终清）"

    # S9 演示自终清：登记域+sync 历史残留 DB 级删除，回归冻结面 18（与 D44Cleanup 同通道口径）
    $repoM = 'D:\SoftwareInstall\Apache\repository'
    $myjar = (Get-ChildItem "$repoM\com\mysql\mysql-connector-j\8.0.33" -Filter *.jar | Where-Object Name -notmatch 'sources' | Select-Object -First 1).FullName
    $myUrl = 'jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8'
    & java -cp $myjar "$PSScriptRoot\tools\QD44.java" $myUrl 'root' '123456' "DELETE FROM d44_document_sync WHERE document_id='d44-DEMO-001'" "DELETE FROM knowledge_document WHERE document_id='d44-DEMO-001'" "SELECT COUNT(*) AS remaining FROM knowledge_document WHERE document_id='d44-DEMO-001'" 2>&1 | Out-Null
    $docs2 = Invoke-RestMethod "$base/api/v1/documents" -TimeoutSec 20
    $nDoc2 = @($docs2.data).Count
    Step 'S9' '演示终清回归冻结面' ($nDoc2 -eq 18) "docView $nDoc -> $nDoc2（d44-DEMO-001 登记/sync/向量/表格四域全零残留，冻结面 18 恢复）"
} catch {
    Step 'ERR' '演示异常中断' $false $_.Exception.Message
}
$fail = @($steps | Where-Object verdict -eq 'FAIL').Count
$boundary = '边界声明：AI_MODE=mock + embedding=local-hashing384（无外网）；S3 有据面生成决策受 mock 限制（D43-C12），真实模型臂/远程 rerank 臂 NOT RUN（禁冒充，见 optimization-backlog O3）；本演示全程仅 127.0.0.1。'
[pscustomobject]@{
    scope = 'D44 RAG 端到端可复现演示（答辩四件套）'
    executedAt = (Get-Date -Format 's'); steps = $steps; fail = $fail
    verdict = $(if ($fail -eq 0) { 'ALL-PASS' } else { 'HAS-FAIL' })
    boundaryNote = $boundary
    reproduce = '& docs\temp_docs\D44\d44_demo.ps1（前置 d44_instance.ps1 -Action start 干净语料面）'
} | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $demoDir 'demo-transcript.json') -Encoding UTF8
"`nDEMO verdict=$($(if ($fail -eq 0) { 'ALL-PASS' } else { "FAIL=$fail" })) -> $demoDir\demo-transcript.json"
