#requires -Version 7.0
# D40 P5 注入矩阵 · 实例 A（FAULT_INJECTION_ENABLED=true, ADD_ATTEMPT=2, DELETE_ATTEMPT=1, batch-size=1）
# 覆盖：E2 部分入库（PARTIAL/EMBED_WRITE_FAILED 落库→注入消耗后重跑→四元组与一次成功一致，C07）
#       E3 删除重试（delete 首批抛→FAILED+PG_UNREACHABLE 落库上抛→恢复手动 /sync/retry→SYNCED 双端 0/0/0，C09）
# 证据：docs\evidence\D40\exceptions\{e2-e3-instance-a.json, update\reentry-fingerprint.json, delete\retry-converge.json}
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

$log = [ordered]@{ instance = 'A(fault-injection: add#2 throw, delete#1 throw, batch=1)'; steps = @() }
function Add-Step($name, $obj) {
    $script:log.steps += [ordered]@{ step = $name; at = (Get-Date -Format 'o'); detail = $obj }
    Write-Host "[STEP] $name"
}
function Snap($phase) {
    & "$jdk\java.exe" -cp $cp D40P5Snapshot "$ev\probe\snap-$phase.json" $phase | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "SNAPSHOT_FAILED:$phase" }
    (Get-Content "$ev\probe\snap-$phase.json" -Raw | ConvertFrom-Json)
}
function Chunk($i, $text) {
    @{ chunkIndex = $i; content = $text; globalStart = 0; globalEnd = $text.Length }
}
function Index-Body($docId, $version, $c0, $c1) {
    @{ documentId = $docId; version = $version; strategy = 'FIXED_SIZE'; title = "D40 fixture $docId"
       chunks = @((Chunk 0 $c0), (Chunk 1 $c1)) } | ConvertTo-Json -Depth 6
}
# f/f2 共享同一正文（文档 id 不进 content，仅进稳定 ID 盐——四元组内容哈希可比）
$T0 = 'D40 注入矩阵共享正文片段一：部分入库幂等重跑证明。'
$T1 = 'D40 注入矩阵共享正文片段二：与一次成功基线逐字节一致。'
function PgCount($snap, $docId) {
    $row = $snap.pg_d40_vector_store_by_doc | Where-Object { $_.documentId -eq $docId }
    if ($row) { $row.rows } else { 0 }
}
function ChunkRows($snap, $docId) {
    @($snap.chunk_index_d40 | Where-Object { $_.documentId -eq $docId } | ForEach-Object { 1..$_.rows })
}

Snap 'injA-pre' | Out-Null
Add-Step 'snap:injA-pre' $null

# A1 登记 f/g/f2（重复登记 4001 如实记录）
foreach ($d in 'd40-doc-f', 'd40-doc-g', 'd40-doc-f2') {
    $r = Invoke-RestMethod -Uri "$base/documents/register" -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body (@{ documentId = $d; source = 'd40-fixture'; title = "D40 fixture $d" } | ConvertTo-Json)
    Add-Step "register:$d" @{ code = $r.code }
}

# A2 E2 首次入库 f v1：add #1 成功、#2 抛（batch=1，2 chunks）→ 预期 PARTIAL/EMBED_WRITE_FAILED
try {
    $r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body (Index-Body 'd40-doc-f' 1 $T0 $T1)
    Add-Step 'E2:index:f:v1(expect FAIL)' @{ code = $r.code; msg = $r.msg }
} catch {
    Add-Step 'E2:index:f:v1(http-exception)' @{ http = $_.Exception.Message }
}
$st = Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-f/status" -Method Get
$snap1 = Snap 'injA-after-e2fail'
$taskF = $st.data | Where-Object { $_.version -eq 1 }
Add-Step 'E2:task:f:v1(expect PARTIAL+EMBED_WRITE_FAILED)' @{ status = $taskF.status; errorCode = $taskF.errorCode; pgCount = (PgCount $snap1 'd40-doc-f'); chunkRows = (ChunkRows $snap1 'd40-doc-f').Count }

# A3 E2 重跑（注入点已消耗=等效"关闭注入"）→ COMPLETED
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (Index-Body 'd40-doc-f' 1 $T0 $T1)
Add-Step 'E2:rerun:f:v1(expect COMPLETED)' @{ code = $r.code; status = $r.data.status }

# A4 参照 f2：同正文一次成功（add 注入位已消耗，正常透传）
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (Index-Body 'd40-doc-f2' 1 $T0 $T1)
Add-Step 'REF:index:f2:v1(one-pass success)' @{ code = $r.code; status = $r.data.status }

# A5 四元组对照：以 D40Quad 直查（update\quad-f-vs-f2.json）为权威证据；此处记录计数
$snap2 = Snap 'injA-after-e2rerun'
$stf = (Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-f/status" -Method Get).data | Where-Object { $_.version -eq 1 }
$stf2 = (Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-f2/status" -Method Get).data | Where-Object { $_.version -eq 1 }
$quad = [ordered]@{
    f_after_rerun = @{ status = $stf.status; contentHash = $stf.contentHash; pgCount = (PgCount $snap2 'd40-doc-f'); relationCount = (ChunkRows $snap2 'd40-doc-f').Count }
    f2_one_pass   = @{ status = $stf2.status; contentHash = $stf2.contentHash; pgCount = (PgCount $snap2 'd40-doc-f2'); relationCount = (ChunkRows $snap2 'd40-doc-f2').Count }
    match = [ordered]@{
        taskContentHashEqual = ($stf.contentHash -eq $stf2.contentHash)
        pgCountEqual         = ($quad.f_after_rerun.pgCount -eq $quad.f2_one_pass.pgCount)
        relationCountEqual   = ($quad.f_after_rerun.relationCount -eq $quad.f2_one_pass.relationCount)
        note                 = 'chunkId/contentHash 逐字节对照以 update\quad-f-vs-f2.json（D40Quad 直查）为权威；chunkId 以 documentId 为 UUIDv5 盐，f/f2 间结构性不同（如实标注）'
    }
    quadRef = 'update\quad-f-vs-f2.json'
}
Add-Step 'C07:quad-f-vs-f2' $quad
$quad | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 "$ev\update\reentry-fingerprint.json"

# A6 E3：g 入库成功 → delete 首批抛（delete #1）→ FAILED+PG_UNREACHABLE 落库上抛
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (Index-Body 'd40-doc-g' 1 "$([char]0x0067) 删除重试夹具内容一：注入 delete 首批抛。" 'g 删除重试夹具内容二。')
Add-Step 'E3:index:g:v1' @{ code = $r.code; status = $r.data.status }
try {
    $r = Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-g" -Method Delete
    Add-Step 'E3:delete:g(expect FAIL PG_UNREACHABLE)' @{ code = $r.code; msg = $r.msg }
} catch {
    Add-Step 'E3:delete:g(http-exception)' @{ http = $_.Exception.Message }
}
$sy = (Invoke-RestMethod -Uri "$base/sync/status?documentId=d40-doc-g" -Method Get).data
$snap3 = Snap 'injA-after-e3fail'
$gRows = ChunkRows $snap3 'd40-doc-g'
$kb = Invoke-RestMethod -Uri "$base/documents/d40-doc-g" -Method Get
Add-Step 'E3:state(expect FAILED+PG_UNREACHABLE, tombstone DEPRECATED, PG g=2)' @{
    syncStatus = $sy.syncStatus; reason = $sy.reasonCode; attempt = $sy.attemptCount
    pgCount = (PgCount $snap3 'd40-doc-g'); chunkRows = $gRows.Count
}

# A7 E3 恢复：注入点已消耗 → 手动 /sync/retry g → SYNCED 双端 0/0/0
Start-Sleep -Seconds 1
$r = Invoke-RestMethod -Uri "$base/sync/retry" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ documentId = 'd40-doc-g' } | ConvertTo-Json)
Add-Step 'E3:sync:retry:g' @{ code = $r.code; results = @($r.data | ForEach-Object { @{ op = $_.operation; st = $_.status } }) }
$sy2 = (Invoke-RestMethod -Uri "$base/sync/status?documentId=d40-doc-g" -Method Get).data
$snap4 = Snap 'injA-after-e3retry'
$conv = [ordered]@{
    sync = @{ status = $sy2.syncStatus; reason = $sy2.reasonCode; attempt = $sy2.attemptCount }
    pgCount_g = (PgCount $snap4 'd40-doc-g'); chunkRows_g = (ChunkRows $snap4 'd40-doc-g').Count
    taskRows_g = @($snap4.vector_index_tasks_d40 | Where-Object { $_.documentId -eq 'd40-doc-g' }).Count
}
Add-Step 'C09:converge(expect SYNCED attempt=1, 0/0/0)' $conv
$conv | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 "$ev\delete\retry-converge.json"

$log | ConvertTo-Json -Depth 9 | Out-File -Encoding utf8 "$ev\exceptions\e2-e3-instance-a.json"
Write-Host '[DONE] INJECT_A_LOGGED'
