#requires -Version 7.0
# D40 P5 注入矩阵 · 实例 B（FAULT_INJECTION_ENABLED=true, DELETE_ATTEMPT=1, batch=100 默认）
# 覆盖：E5 旧版本残留（cleanupSuperseded delete 抛→UPDATE_CLEANUP FAILED 落库不上抛）
#       C11 清理失败同步（sync retry 收敛 SYNCED 残留归零）
#       C12 核查命中（DRIFT_STALE_VERSION + DRIFT_SYNC_STUCK 于 d40 域自然构造；
#           META_ONLY/VECTOR_ONLY 由 d32eval 域既有真实漂移在 dryRun 全域报告中命中）
#       C13 修复走管线（consistency 非 dryRun 限 prefix + sync retry 双路，复查归零；
#           grep 证明修复代码零裸 SQL 直改业务表——见脚本尾注与证据文档）
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8080/api/v1'
$root = 'E:\AI\project\AI-Learning-one\M3-Java'
$ev   = "$root\docs\evidence\D40"
$tp   = "$root\docs\temp_docs\D40"
$jdk  = 'D:\SoftwareInstall\JDK\JDK21\bin'
$repo = 'D:\SoftwareInstall\Apache\repository'
$cp   = "$tp\probe\classes;$repo\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar;$repo\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar;$repo\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar;$repo\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar;$repo\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
$env:MYSQL_PASSWORD = '123456'
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')

$log = [ordered]@{ instance = 'B(fault-injection: delete#1 throw, batch=100)'; steps = @() }
function Add-Step($name, $obj) {
    $script:log.steps += [ordered]@{ step = $name; at = (Get-Date -Format 'o'); detail = $obj }
    Write-Host "[STEP] $name"
}
function Snap($phase) {
    & "$jdk\java.exe" -cp $cp D40P5Snapshot "$ev\probe\snap-$phase.json" $phase | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "SNAPSHOT_FAILED:$phase" }
    (Get-Content "$ev\probe\snap-$phase.json" -Raw | ConvertFrom-Json)
}
function PgRows($snap, $doc) { $r = $snap.pg_d40_vector_store_by_doc | Where-Object documentId -eq $doc; if ($r) { $r.rows } else { 0 } }
function ChRows($snap, $doc) { $r = $snap.chunk_index_d40 | Where-Object documentId -eq $doc; if ($r) { $r.rows } else { 0 } }
function Chunk($i, $text) { @{ chunkIndex = $i; content = $text; globalStart = 0; globalEnd = $text.Length } }
function Index-Body($docId, $version, $c0, $c1) {
    @{ documentId = $docId; version = $version; strategy = 'FIXED_SIZE'; title = "D40 fixture $docId"
       chunks = @((Chunk 0 $c0), (Chunk 1 $c1)) } | ConvertTo-Json -Depth 6
}

Snap 'injB-pre' | Out-Null
Add-Step 'snap:injB-pre' $null

# B1 登记并 v1 入库 h（add 无注入位）
$r = Invoke-RestMethod -Uri "$base/documents/register" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ documentId = 'd40-doc-h'; source = 'd40-fixture'; title = 'D40 fixture h(E5 旧版残留)' } | ConvertTo-Json)
Add-Step 'register:h' @{ code = $r.code }
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (Index-Body 'd40-doc-h' 1 'h 旧版内容：cleanupSuperseded 注入失败夹具。' 'h 旧版第二片段。')
Add-Step 'index:h:v1' @{ code = $r.code; status = $r.data.status }

# B2 E5 构造：index h v2 → SWITCH → cleanupSuperseded 阶段 delete #1 抛（非阻断：接口仍 200/COMPLETED）
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (Index-Body 'd40-doc-h' 2 'h 新版内容：v2 权威，旧版清理应失败留残。' 'h 新版第二片段。')
$sy = (Invoke-RestMethod -Uri "$base/sync/status?documentId=d40-doc-h" -Method Get).data
$snap1 = Snap 'injB-after-e5fail'
$st = (Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-h/status" -Method Get).data
Add-Step 'E5:index:h:v2(non-blocking COMPLETED + cleanup FAILED)' @{
    code = $r.code; returnedStatus = $r.data.status
    tasks = @($st | ForEach-Object { @{ v = $_.version; s = $_.status } })
    sync = @($sy | ForEach-Object { @{ op = $_.operation; v = $_.version; st = $_.syncStatus; reason = $_.reasonCode; attempt = $_.attemptCount } })
    pg_h = (PgRows $snap1 'd40-doc-h'); chunk_h = (ChRows $snap1 'd40-doc-h')
}

# B3 C12 检测：d40 域 dryRun（STALE_VERSION+SYNC_STUCK 命中）+ 全域 dryRun（META/VECTOR_ONLY d32eval 既有漂移命中）
Start-Sleep -Seconds 2   # 让 FAILED 行 next_retry_at(now+500ms) 到期，SYNC_STUCK 判定成立
$c1 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $true; documentPrefix = 'd40-doc-' } | ConvertTo-Json)
$c2 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $true } | ConvertTo-Json)
$detect = [ordered]@{ scopeD40 = $c1.data; scopeGlobal = $c2.data }
$detect | ConvertTo-Json -Depth 12 | Out-File -Encoding utf8 "$ev\consistency\detect-report.json"
Add-Step 'C12:detect(d40域 expect STALE>=2 & STUCK>=1; 全域 expect META/VECTOR_ONLY>0)' @{
    d40_driftSummary = $c1.data.driftSummary
    global_driftSummary = $c2.data.driftSummary
}

# B4 C13 修复路径一：consistency 非 dryRun（强制 prefix，H6）——孤儿补偿走 deleteVectorsByIds 管线
$c3 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $false; documentPrefix = 'd40-doc-' } | ConvertTo-Json)
$snap2 = Snap 'injB-after-reconcile-repair'
Add-Step 'C13:consistency-repair(non-dryRun prefix=d40-doc-)' @{
    code = $c3.code; actionsTaken = $c3.data.actionsTaken
    pg_h = (PgRows $snap2 'd40-doc-h'); chunk_h = (ChRows $snap2 'd40-doc-h')
}
# B4b 无 prefix 非 dryRun → 4001 门禁（H6 夹具域隔离断言）
$c4 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $false } | ConvertTo-Json)
Add-Step 'H6:repair-without-prefix(expect 4001)' @{ code = $c4.code; msg = $c4.msg }

# B5 C11/E5 收敛：sync retry 把 UPDATE_CLEANUP FAILED 行收敛 SYNCED（幂等确认；若修复已清则走 0/0 确认）
$r = Invoke-RestMethod -Uri "$base/sync/retry" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ documentId = 'd40-doc-h' } | ConvertTo-Json)
$sy2 = (Invoke-RestMethod -Uri "$base/sync/status?documentId=d40-doc-h" -Method Get).data
Add-Step 'C11:sync:retry:h' @{
    code = $r.code; results = @($r.data | ForEach-Object { @{ op = $_.operation; v = $_.version; st = $_.status } })
    sync = @($sy2 | ForEach-Object { @{ op = $_.operation; v = $_.version; st = $_.syncStatus; reason = $_.reasonCode; attempt = $_.attemptCount } })
}

# B6 复查归零：d40 域 dryRun 再核查
$c5 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $true; documentPrefix = 'd40-doc-' } | ConvertTo-Json)
$snap3 = Snap 'injB-final'
$recheck = [ordered]@{
    repairActions = $c3.data.actionsTaken
    retryResults  = @($r.data | ForEach-Object { @{ op = $_.operation; v = $_.version; st = $_.status } })
    recheckD40    = $c5.data
    pg_h_final    = (PgRows $snap3 'd40-doc-h'); chunk_h_final = (ChRows $snap3 'd40-doc-h')
    note          = '修复全部经既有管线：consistency 修复调 indexService.deleteVectorsByIds / indexChunks（无裸 SQL 直改业务表，grep 证据见证据文档 C13 节）；sync 行经 retryCleanupVersion 收敛'
}
$recheck | ConvertTo-Json -Depth 12 | Out-File -Encoding utf8 "$ev\consistency\repair-recheck.json"
$cleanupSync = [ordered]@{ scenario = 'E5 cleanupSuperseded 注入失败(cleanup delete#1 抛)'; nonBlocking = '接口 200 v2 COMPLETED，失败落库不上抛'; detect = 'C12 步骤 B3'; repair = 'B4 consistency 管线 + B5 sync retry'; recheck = 'B6 d40 域归零'; evidence = 'consistency\detect-report.json + consistency\repair-recheck.json + probe\snap-injB-*.json' }
$cleanupSync | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 "$ev\update\cleanup-sync.json"
Add-Step 'C13+E5:recheck(expect d40域 STALE=0 STUCK=0, pg_h=2)' @{ driftSummary = $c5.data.driftSummary; pg_h = $recheck.pg_h_final }

$log | ConvertTo-Json -Depth 12 | Out-File -Encoding utf8 "$ev\exceptions\e5-instance-b.json"
Write-Host '[DONE] INJECT_B_LOGGED'
