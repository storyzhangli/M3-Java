#requires -Version 7.0
# D40 P5-C12b：五类漂移全命中补验（实例 B 态，注入点已消耗）——KP5 追加裁决授权的单步受控构造
# 序：夹具 j/k 入库 → 留痕 snapshot → 三步 construct → detect(全命中) → consistency 非 dryRun 管线修复 → recheck 归零
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8080/api/v1'
$root = 'E:\AI\project\AI-Learning-one\M3-Java'
$ev   = "$root\docs\evidence\D40"
$tp   = "$root\docs\temp_docs\D40"
$jdk  = 'D:\SoftwareInstall\JDK\JDK21\bin'
$repo = 'D:\SoftwareInstall\Apache\repository'
$cpJ  = "$tp\probe\classes;$repo\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar;$repo\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar;$repo\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar;$repo\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar;$repo\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
$env:MYSQL_PASSWORD = '123456'
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')
$log = [ordered]@{ scenario = 'C12b 五类漂移全命中+管线修复（KP5 裁决）'; steps = @() }
function Add-Step($name, $obj) { $script:log.steps += [ordered]@{ step = $name; at = (Get-Date -Format 'o'); detail = $obj }; Write-Host "[STEP] $name" }
function Chunk($i, $text) { @{ chunkIndex = $i; content = $text; globalStart = 0; globalEnd = $text.Length } }
function Index-Body($docId, $version, $c0, $c1) {
    @{ documentId = $docId; version = $version; strategy = 'FIXED_SIZE'; title = "D40 fixture $docId"
       chunks = @((Chunk 0 $c0), (Chunk 1 $c1)) } | ConvertTo-Json -Depth 6
}

foreach ($d in 'd40-doc-j', 'd40-doc-k') {
    $r = Invoke-RestMethod -Uri "$base/documents/register" -Method Post -ContentType 'application/json; charset=utf-8' `
        -Body (@{ documentId = $d; source = 'd40-fixture'; title = "D40 fixture $d(C12b)" } | ConvertTo-Json)
    Add-Step "register:$d" @{ code = $r.code }
}
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' -Body (Index-Body 'd40-doc-j' 1 'j 片段0：META_ONLY 构造载体。' 'j 片段1：重建修复回放源。')
Add-Step 'index:j:v1' @{ code = $r.code; st = $r.data.status }
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' -Body (Index-Body 'd40-doc-k' 1 'k 片段0：VECTOR_ONLY 构造载体。' 'k 片段1：v1。')
Add-Step 'index:k:v1' @{ code = $r.code; st = $r.data.status }
$r = Invoke-RestMethod -Uri "$base/vector-index/index" -Method Post -ContentType 'application/json; charset=utf-8' -Body (Index-Body 'd40-doc-k' 2 'k 新版片段0。' 'k 新版片段1。')
Add-Step 'index:k:v2' @{ code = $r.code; st = $r.data.status }

& "$jdk\java.exe" -cp $cpJ D40DriftConstruct "$ev\consistency\c12b-drift-snapshot.json" snapshot | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'SNAPSHOT_FAIL' }
& "$jdk\java.exe" -cp $cpJ D40DriftConstruct "$ev\consistency\c12b-drift-construct.json" construct | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'CONSTRUCT_FAIL' }
Add-Step 'construct(META删j1行PG/VECTOR删k关联行/MISMATCH j→9 k→3)' $null

$c1 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $true; documentPrefix = 'd40-doc-' } | ConvertTo-Json)
$det = [ordered]@{ driftSummary = $c1.data.driftSummary
    missing = @($c1.data.missingVectors | ForEach-Object { $_.key })
    orphan  = @($c1.data.orphanVectors  | ForEach-Object { @{ key = $_.key; doc = $_.documentId; type = $_.type } })
    versionMismatches = @($c1.data.versionMismatches | ForEach-Object { @{ key = $_.key; doc = $_.documentId } })
}
$det | ConvertTo-Json -Depth 10 | Out-File -Encoding utf8 "$ev\consistency\c12b-detect.json"
Add-Step 'C12:detect(expect META>=1 VECTOR=2 VERSION=2)' $det.driftSummary

$c2 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $false; documentPrefix = 'd40-doc-' } | ConvertTo-Json)
Add-Step 'C13:repair(pipeline-only)' @{ actions = $c2.data.actionsTaken }

$c3 = Invoke-RestMethod -Uri "$base/sync/consistency" -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body (@{ dryRun = $true; documentPrefix = 'd40-doc-' } | ConvertTo-Json)
$pj = (Invoke-RestMethod -Uri "$base/documents/d40-doc-j" -Method Get).data.version
$pk = (Invoke-RestMethod -Uri "$base/documents/d40-doc-k" -Method Get).data.version
$recheck = [ordered]@{ d40_driftSummary = $c3.data.driftSummary; pointer_j = $pj; pointer_k = $pk
    expect = 'd40 域 META/VECTOR/VERSION/STALE/STUCK 全 0；j 指针回 1、k 回 2（repointVersionPointer 管线）' }
$recheck | ConvertTo-Json -Depth 10 | Out-File -Encoding utf8 "$ev\consistency\c12b-repair-recheck.json"
Add-Step 'recheck(归零)' @{ d40 = $c3.data.driftSummary; pointer_j = $pj; pointer_k = $pk }

$log | ConvertTo-Json -Depth 12 | Out-File -Encoding utf8 "$ev\exceptions\c12b-instance-b.json"
Write-Host '[DONE] C12B_LOGGED'
