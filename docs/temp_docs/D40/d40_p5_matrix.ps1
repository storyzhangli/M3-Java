#requires -Version 7.0
# D40 P5 矩阵（disabled 默认实例态）：更新闭环 / 删除闭环 / E4 重复删除 / tombstone / E1 解析失败
# 证据：docs\evidence\D40\{update,delete,exceptions}\ ；每步 DB 事实快照由 D40P5Snapshot harness 落盘。
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
foreach ($sub in 'update', 'delete', 'exceptions', 'consistency', 'security') {
    New-Item -ItemType Directory -Force "$ev\$sub" | Out-Null
}

$log = [ordered]@{ steps = @() }
function Add-Step($name, $obj) {
    $script:log.steps += [ordered]@{ step = $name; at = (Get-Date -Format 'o'); detail = $obj }
    Write-Host "[STEP] $name"
}
function Snap($phase) {
    & "$jdk\java.exe" -cp $cp D40P5Snapshot "$ev\probe\snap-$phase.json" $phase | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "SNAPSHOT_FAILED:$phase" }
}
function Chunk($i, $text) {
    @{ chunkIndex = $i; content = $text; globalStart = 0; globalEnd = $text.Length }
}
function Index-Body($docId, $version, $c0, $c1) {
    @{ documentId = $docId; version = $version; strategy = 'FIXED_SIZE'; title = "D40 fixture $docId"
       chunks = @((Chunk 0 $c0), (Chunk 1 $c1)) } | ConvertTo-Json -Depth 6
}

Snap 'p5-pre'
Add-Step 'snap-pre' $null

# R1 登记 5 个夹具文档
foreach ($d in 'd40-doc-a', 'd40-doc-b', 'd40-doc-c', 'd40-doc-d', 'd40-doc-up') {
    $r = Invoke-RestMethod -Uri "$base/documents/register" -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body (@{ documentId = $d; source = 'd40-fixture'; title = "D40 fixture $d" } | ConvertTo-Json)
    Add-Step "register:$d" @{ code = $r.code; version = $r.data.version; lifecycle = $r.data.lifecycleStatus }
}
# R1b 重复登记 → 4001
$r = Invoke-RestMethod -Uri "$base/documents/register" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ documentId = 'd40-doc-a'; source = 'd40-fixture'; title = 'dup' } | ConvertTo-Json)
Add-Step 'register-dup-expect-4001' @{ code = $r.code }

# R2 a/b/c/d v1 入库
foreach ($d in 'd40-doc-a', 'd40-doc-b', 'd40-doc-c', 'd40-doc-d') {
    $r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body (Index-Body $d 1 "$d 内容一：D40 删除闭环夹具片段。" "$d 内容二：D40 引用交叉夹具片段。")
    Add-Step "index:$d:v1" @{ code = $r.code; status = $r.data.status; embedded = $r.data.embeddedCount }
}

# R3 更新闭环 up v1→v2（SWITCH+cleanupSuperseded+版本指针权威化）
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (Index-Body 'd40-doc-up' 1 'up 旧版内容。' 'up 旧版第二片段。')
Add-Step 'index:up:v1' @{ code = $r.code; status = $r.data.status }
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (Index-Body 'd40-doc-up' 2 'up 新版内容：v2 权威。' 'up 新版第二片段。')
Add-Step 'index:up:v2' @{ code = $r.code; status = $r.data.status }
$st = Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-up/status" -Method Get
Add-Step 'status:up' ($st.data | ForEach-Object { @{ v = $_.version; s = $_.status } })
$kd = Invoke-RestMethod -Uri "$base/documents/d40-doc-up" -Method Get
Add-Step 'pointer:up(expect version=2)' @{ code = $kd.code; version = $kd.data.version; lifecycle = $kd.data.lifecycleStatus }
Snap 'p5-after-update'

# R4 删除闭环 a
$r = Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-a" -Method Delete
Add-Step 'delete:a' @{ code = $r.code; removedBefore = $r.data }
$sy = Invoke-RestMethod -Uri "$base/sync/status?documentId=d40-doc-a" -Method Get
Add-Step 'sync:a(expect SYNCED)' ($sy.data | ForEach-Object { @{ op = $_.operation; st = $_.syncStatus; reason = $_.reasonCode; attempt = $_.attemptCount } })
Snap 'p5-after-delete-a'

# R5 E4 重复删除（幂等短路，sync 行零变化）
$r = Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-a" -Method Delete
$sy2 = Invoke-RestMethod -Uri "$base/sync/status?documentId=d40-doc-a" -Method Get
Add-Step 'delete:a:again(expect short-circuit)' @{ code = $r.code; removedBefore = $r.data; sync = ($sy2.data | ForEach-Object { @{ st = $_.syncStatus; attempt = $_.attemptCount; hash12 = $_.payloadHash.Substring(0, 12) } }) }

# R6 删除 b + tombstone 断言
$r = Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-b" -Method Delete
$kb = Invoke-RestMethod -Uri "$base/documents/d40-doc-b" -Method Get
$syb = Invoke-RestMethod -Uri "$base/sync/status?documentId=d40-doc-b" -Method Get
Add-Step 'delete:b+tombstone' @{ delCode = $r.code; docRowExists = ($null -ne $kb.data); lifecycle = $kb.data.lifecycleStatus; sync = ($syb.data | ForEach-Object { @{ st = $_.syncStatus; attempt = $_.attemptCount } }) }

# R7 删除 c（供 C17 中间态核查：chunk 行=0，DEPRECATED）
$r = Invoke-RestMethod -Uri "$base/vector-index/documents/d40-doc-c" -Method Delete
Add-Step 'delete:c' @{ code = $r.code }

# R8 E1 畸形导入（非 PDF 字节流按 .pdf 上传）
$tmpPdf = "$tp\matrix-e1-broken.pdf"
[IO.File]::WriteAllBytes($tmpPdf, [byte[]](0x4E, 0x4F, 0x54, 0x2D, 0x41, 0x2D, 0x50, 0x44, 0x46, 0x0A))
try {
    $r = Invoke-RestMethod -Uri "$base/documents/import" -Method Post -Form @{ file = Get-Item $tmpPdf; documentId = 'd40-doc-e1'; source = 'd40-fixture'; title = 'E1 broken pdf' }
    Add-Step 'import:e1(expect FAIL code)' @{ code = $r.code; msg = $r.msg }
} catch {
    Add-Step 'import:e1(exception-path)' @{ http = $_.Exception.Message }
}
Snap 'p5-final'

$log | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 "$ev\exceptions\p5-matrix.json"
Write-Host '[DONE] P5_MATRIX_LOGGED'
