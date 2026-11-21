# one_index-corpus.ps1 — D38 P3/C08：one_ 轨真实入库验证
# 用例：8/8 COMPLETED + 三方计数；幂等重跑短路（零远程调用）；
#       换 strategy 先 DELETE 再重建（D37 教训：自然键含 strategy 且 upsert 不更新 chunk_id）；
#       reconcile dryRun 全一致；usage 真实计数核算。
# 已知边界：直接入库 API 无 page/section 承载字段 → PG 侧 page=null，如实记录不伪造。
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://localhost:8080"
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$evDir = Join-Path $repo "docs\evidence\D38\index"
New-Item -ItemType Directory -Force -Path $evDir | Out-Null
$spaceId = "bailian|qwen3.7-text-embedding|remote-v1|1024"

function Get-Usage {
    (Invoke-RestMethod -Uri "$base/api/v1/one/embeddings/usage" -Method Get).data
}
function Invoke-Json($method, $url, $bodyObj) {
    if ($null -ne $bodyObj) {
        $json = $bodyObj | ConvertTo-Json -Depth 8
        return Invoke-RestMethod -Uri $url -Method $method -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json))
    }
    return Invoke-RestMethod -Uri $url -Method $method
}

# ---- 语料（顶层数组，3 文档 8 片段；globalStart/End 按 excerpt 长度累加）----
$corpus = Get-Content -LiteralPath (Join-Path $repo "D38\D38_QWE_MROQRE\src\main\resources\knowledge\equipment-fault-corpus.json") -Raw -Encoding UTF8 | ConvertFrom-Json
function New-IndexBody($doc, $strategy) {
    $chunks = @(); $offset = 0
    for ($i = 0; $i -lt $doc.chunks.Count; $i++) {
        $text = $doc.chunks[$i].excerpt
        $chunks += @{ chunkIndex = $i; content = $text; globalStart = $offset; globalEnd = $offset + $text.Length }
        $offset += $text.Length
    }
    return @{ documentId = $doc.documentId; version = 1; strategy = $strategy; title = $doc.title; chunks = $chunks }
}

$usage0 = Get-Usage
Write-Output "[USAGE-0] calls=$($usage0.calls) tokens=$($usage0.totalTokens)"

# ---- 1) 逐文档 DELETE（清场幂等）+ STRUCTURE 入库 ----
$indexResults = @()
foreach ($doc in $corpus) {
    $del = Invoke-Json Delete "$base/api/v1/one/vector-index/documents/$($doc.documentId)" $null
    $res = Invoke-Json Post "$base/api/v1/one/vector-index/index" (New-IndexBody $doc "STRUCTURE")
    $d = $res.data
    if ($d.status -ne "COMPLETED" -or $d.chunkCount -ne $doc.chunks.Count -or $d.vectorSpaceId -ne $spaceId) {
        throw "C08 入库未达标：$($doc.documentId) status=$($d.status) chunks=$($d.chunkCount) space=$($d.vectorSpaceId)"
    }
    $indexResults += [pscustomobject]@{ documentId = $doc.documentId; deletedBefore = $del.data; result = $d }
    Write-Output "[INDEX] $($doc.documentId) COMPLETED chunks=$($d.chunkCount) processed=$($d.processed)"
}
$indexResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evDir "index-results.json") -Encoding UTF8
$usage1 = Get-Usage
$firstPassCalls = $usage1.calls - $usage0.calls
if ($firstPassCalls -lt 1) { throw "C08 首轮入库未产生真实远程调用——计数链路异常" }
Write-Output "[USAGE-1] calls=$($usage1.calls) (+$firstPassCalls) tokens=$($usage1.totalTokens)"

# ---- 2) 幂等重跑：必须短路且零新增远程调用 ----
$idemResults = @()
foreach ($doc in $corpus) {
    $res = Invoke-Json Post "$base/api/v1/one/vector-index/index" (New-IndexBody $doc "STRUCTURE")
    $d = $res.data
    if ($d.idempotent -ne $true -or $d.processed -ne 0) {
        throw "C08 幂等短路失败：$($doc.documentId) idempotent=$($d.idempotent) processed=$($d.processed)"
    }
    $idemResults += $d
    Write-Output "[IDEMPOTENT] $($doc.documentId) idempotent=true processed=0"
}
$usage2 = Get-Usage
if ($usage2.calls -ne $usage1.calls) { throw "C08 幂等重跑产生了远程调用（$($usage1.calls)→$($usage2.calls)）" }
$idemResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evDir "idempotent-results.json") -Encoding UTF8
Write-Output "[IDEMPOTENT] 零新增调用核实 calls=$($usage2.calls)"

# ---- 3) 状态/关联明细 + vectorSpaceId 逐行核验 ----
$statusChunks = @()
foreach ($doc in $corpus) {
    $st = (Invoke-Json Get "$base/api/v1/one/vector-index/documents/$($doc.documentId)/status" $null).data
    $ch = (Invoke-Json Get "$base/api/v1/one/vector-index/documents/$($doc.documentId)/chunks" $null).data
    foreach ($row in $st) { if ($row.vectorSpaceId -ne $spaceId) { throw "C08 状态行 spaceId 异常：$($row.vectorSpaceId)" } }
    foreach ($row in $ch) { if ($row.vectorSpaceId -ne $spaceId) { throw "C08 关联行 spaceId 异常：$($row.vectorSpaceId)" } }
    $statusChunks += [pscustomobject]@{ documentId = $doc.documentId; status = $st; chunks = $ch }
}
$statusChunks | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $evDir "status-chunks.json") -Encoding UTF8

# ---- 4) 换 strategy 路径：DOC-WORKORDER-SPEC-003 DELETE→FIXED_SIZE 重建→核验 chunkId 变化→恢复 STRUCTURE ----
$switchDoc = $corpus | Where-Object { $_.documentId -eq "DOC-WORKORDER-SPEC-003" }
$idsStructure = ($statusChunks | Where-Object { $_.documentId -eq $switchDoc.documentId }).chunks | ForEach-Object { $_.chunkId }
Invoke-Json Delete "$base/api/v1/one/vector-index/documents/$($switchDoc.documentId)" $null | Out-Null
$resFixed = (Invoke-Json Post "$base/api/v1/one/vector-index/index" (New-IndexBody $switchDoc "FIXED_SIZE")).data
if ($resFixed.status -ne "COMPLETED" -or $resFixed.strategy -ne "FIXED_SIZE") { throw "C08 FIXED_SIZE 重建失败" }
$chFixed = (Invoke-Json Get "$base/api/v1/one/vector-index/documents/$($switchDoc.documentId)/chunks" $null).data
$idsFixed = $chFixed | ForEach-Object { $_.chunkId }
$sameIds = @(Compare-Object $idsStructure $idsFixed -SyncWindow 0).Count -eq 0
if ($sameIds) { throw "C08 换 strategy 后 chunkId 未变化——自然键/ID 生成异常" }
# 恢复冻结语料态（STRUCTURE），保证后续评测语料一致
Invoke-Json Delete "$base/api/v1/one/vector-index/documents/$($switchDoc.documentId)" $null | Out-Null
$resRestore = (Invoke-Json Post "$base/api/v1/one/vector-index/index" (New-IndexBody $switchDoc "STRUCTURE")).data
if ($resRestore.status -ne "COMPLETED") { throw "C08 STRUCTURE 恢复失败" }
$chRestore = (Invoke-Json Get "$base/api/v1/one/vector-index/documents/$($switchDoc.documentId)/chunks" $null).data
$idsRestore = $chRestore | ForEach-Object { $_.chunkId }
$restoredSame = @(Compare-Object $idsStructure $idsRestore -SyncWindow 0).Count -eq 0
if (-not $restoredSame) { throw "C08 恢复 STRUCTURE 后 chunkId 与首轮不一致——稳定 ID 非确定性" }
[pscustomobject]@{
    documentId = $switchDoc.documentId
    structureChunkIds = $idsStructure
    fixedSizeChunkIds = $idsFixed
    idsChangedOnStrategySwitch = (-not $sameIds)
    restoredStructureChunkIds = $idsRestore
    restoredIdsIdentical = $restoredSame
    fixedResult = $resFixed; restoreResult = $resRestore
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evDir "strategy-switch.json") -Encoding UTF8
Write-Output "[STRATEGY-SWITCH] DELETE→FIXED_SIZE chunkId 全变=$(-not $sameIds)；恢复 STRUCTURE 稳定 ID 复现=$restoredSame"

# ---- 5) reconcile dryRun：one_ 表对全一致 ----
$rec = (Invoke-Json Post "$base/api/v1/one/vector-index/reconcile?dryRun=true" $null).data
if ($rec.orphanPgTotal -ne 0 -or $rec.missingPgTotal -ne 0) { throw "C08 核对不一致 orphan=$($rec.orphanPgTotal) missing=$($rec.missingPgTotal)" }
foreach ($e in $rec.entries) {
    if (-not $e.consistent -or $e.pgCount -ne $e.relationCount) { throw "C08 条目不一致 $($e.documentId) v$($e.version)" }
}
$rec | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evDir "reconcile.json") -Encoding UTF8
Write-Output "[RECONCILE] entries=$($rec.entries.Count) orphan=0 missing=0 全一致"

# ---- 6) usage 核算落盘 ----
$usage3 = Get-Usage
[pscustomobject]@{
    before = $usage0; afterFirstPass = $usage1; afterIdempotent = $usage2; final = $usage3
    firstPassCalls = $firstPassCalls
    idempotentDeltaCalls = ($usage2.calls - $usage1.calls)
    strategySwitchCalls = ($usage3.calls - $usage2.calls)
    totalCallsThisScript = ($usage3.calls - $usage0.calls)
    totalTokensThisScript = ($usage3.totalTokens - $usage0.totalTokens)
    note = "calls 为应用 LongAdder 真实累计；tokens 来自 API usage 真实返回；换 strategy 段含 FIXED_SIZE 重建+STRUCTURE 恢复两次真实入库"
} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evDir "usage-accounting.json") -Encoding UTF8
Write-Output "[USAGE-FINAL] calls=$($usage3.calls) tokens=$($usage3.totalTokens) 本脚本新增调用=$($usage3.calls - $usage0.calls)"
Write-Output "[C08-PASS] one_ 入库 8/8 COMPLETED、幂等短路零调用、换 strategy DELETE 重建、核对全一致"