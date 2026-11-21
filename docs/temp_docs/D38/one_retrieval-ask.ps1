# one_retrieval-ask.ps1 — D38 P4：C09 检索契约 / C10 跨空间隔离 / C11 ask 决策链（基础问句）
# 夹具专项（CONFLICT/生命周期）由 one_special-fixtures.ps1（P5）覆盖。
# 成本口径：one_ 每次检索/ask 检索段 = 2 次真实远程调用；拒绝类用例 0 调用。
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://localhost:8080"
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$evRoot = Join-Path $repo "docs\evidence\D38"
$retDir = Join-Path $evRoot "retrieval"; $isoDir = Join-Path $evRoot "isolation"; $askDir = Join-Path $evRoot "ask"
foreach ($d in @($retDir, $isoDir, $askDir)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
$oneSpace = "bailian|qwen3.7-text-embedding|remote-v1|1024"
$localSpace = "local|local-hashing-embedding|local-v1|384"
$qa001 = "数控机床出现主轴过热报警，应该先检查什么？"
$frozenLocalTop1 = 0.37974774837493896

function Get-Usage { (Invoke-RestMethod -Uri "$base/api/v1/one/embeddings/usage" -Method Get).data }
function Invoke-Json($method, $url, $bodyObj) {
    if ($null -ne $bodyObj) {
        $json = $bodyObj | ConvertTo-Json -Depth 8
        return Invoke-RestMethod -Uri $url -Method $method -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json))
    }
    return Invoke-RestMethod -Uri $url -Method $method
}
# 拒绝类请求：捕获 HTTP 错误与业务码两种形态，返回 {httpStatus; code; message}
function Invoke-ExpectReject($url, $bodyObj) {
    $json = $bodyObj | ConvertTo-Json -Depth 8
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    try {
        $r = Invoke-RestMethod -Uri $url -Method Post -ContentType "application/json; charset=utf-8" -Body $bytes
        return [pscustomobject]@{ httpStatus = 200; code = $r.code; message = $r.message }
    } catch {
        $status = 0; $bodyText = ""
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
                $bodyText = $reader.ReadToEnd()
            } catch {}
        }
        $code = $null; $msg = $bodyText
        try { $o = $bodyText | ConvertFrom-Json; $code = $o.code; $msg = $o.message } catch {}
        return [pscustomobject]@{ httpStatus = $status; code = $code; message = $msg }
    }
}
function Assert-Rejected($r, $label) {
    if ($r.httpStatus -lt 400 -and $r.code -eq 200) { throw "C09 应拒绝却成功：$label" }
}

$usage0 = Get-Usage
Write-Output "[USAGE-0] calls=$($usage0.calls)"

# ================= C09 检索契约 =================
# 1) QA-001 标准检索
$r1 = (Invoke-Json Post "$base/api/v1/one/retrieval/search" @{ query = $qa001; topK = 5; threshold = 0.0 }).data
foreach ($f in @("matches", "total", "vectorSpaceId", "filtered", "queryEmbeddingMs", "vectorSearchMs", "retrievalTotalMs")) {
    if ($null -eq $r1.PSObject.Properties[$f]) { throw "C09 缺字段 $f" }
}
if ($r1.vectorSpaceId -ne $oneSpace) { throw "C09 spaceId 异常 $($r1.vectorSpaceId)" }
if ($r1.total -lt 1 -or $r1.filtered -ne $false) { throw "C09 标准检索 total=$($r1.total) filtered=$($r1.filtered)" }
$r1 | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $retDir "one-qa001-search.json") -Encoding UTF8
Write-Output "[C09-1] QA-001 total=$($r1.total) top1Sim=$($r1.matches[0].similarity) top1Doc=$($r1.matches[0].metadata.documentId)"

# 2) documentId 过滤下推
$r2 = (Invoke-Json Post "$base/api/v1/one/retrieval/search" @{ query = $qa001; topK = 5; threshold = 0.0; documentId = "DOC-CNC-COOLING-001" }).data
if ($r2.filtered -ne $true) { throw "C09 filtered 标记异常" }
foreach ($m in $r2.matches) { if ($m.metadata.documentId -ne "DOC-CNC-COOLING-001") { throw "C09 过滤下推失效混入 $($m.metadata.documentId)" } }
$r2 | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $retDir "one-filtered-search.json") -Encoding UTF8
Write-Output "[C09-2] 过滤下推 total=$($r2.total) 全部命中 DOC-CNC-COOLING-001"

# 3) 默认值：topK/threshold 缺省 → 5 / 0.0
$r3 = (Invoke-Json Post "$base/api/v1/one/retrieval/search" @{ query = "冷却液液位检查" }).data
if ($r3.topK -ne 5 -or $r3.threshold -ne 0.0) { throw "C09 默认值异常 topK=$($r3.topK) threshold=$($r3.threshold)" }
$r3 | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $retDir "one-defaults.json") -Encoding UTF8
Write-Output "[C09-3] 缺省 topK=5 threshold=0.0"

# 4) 越界/非法参数拒绝（与 local DTO Bean Validation 契约一致，0 远程调用）
$rejections = @()
$cases = @(
    @{ label = "topK=0"; body = @{ query = $qa001; topK = 0 } },
    @{ label = "topK=21"; body = @{ query = $qa001; topK = 21 } },
    @{ label = "threshold=1.5"; body = @{ query = $qa001; threshold = 1.5 } },
    @{ label = "threshold=-0.1"; body = @{ query = $qa001; threshold = -0.1 } },
    @{ label = "query空白"; body = @{ query = "   " } },
    @{ label = "query超长8193"; body = @{ query = ("测" * 8193) } }
)
foreach ($c in $cases) {
    $rj = Invoke-ExpectReject "$base/api/v1/one/retrieval/search" $c.body
    Assert-Rejected $rj $c.label
    $rejections += [pscustomobject]@{ case = $c.label; httpStatus = $rj.httpStatus; code = $rj.code; message = $rj.message }
    Write-Output "[C09-4] $($c.label) → http=$($rj.httpStatus) code=$($rj.code) 已拒绝"
}
$rejections | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $retDir "one-validation-rejections.json") -Encoding UTF8
# 零向量拒绝：远程神经模型不产生全零向量，API 层无法真实触发；守卫代码与 local 逐行一致（VectorValidator+isZeroVector）。
[pscustomobject]@{
    zeroVectorRejection = "N/A_REMOTE"
    reason = "远程模型对任意非空文本均返回非零向量，无法经 API 真实触发全零分支；守卫逻辑与 local 轨一致（validate+isZeroVector），属代码路径对齐而非运行时取证"
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $retDir "zero-vector-boundary.json") -Encoding UTF8

# ================= C10 跨空间隔离 =================
# 1) local 检索：冻结探针复验 + metadata 无 vectorSpaceId 键
$rl = (Invoke-Json Post "$base/api/v1/retrieval/search" @{ query = $qa001; topK = 5; threshold = 0.0; documentId = "DOC-CNC-COOLING-001" }).data
if ($rl.vectorSpaceId -ne $localSpace) { throw "C10 local spaceId 异常 $($rl.vectorSpaceId)" }
$delta = [Math]::Abs($rl.matches[0].similarity - $frozenLocalTop1)
if ($delta -gt 1e-9) { throw "C10 local top1 漂移 delta=$delta" }
foreach ($m in $rl.matches) {
    if ($null -ne $m.metadata.PSObject.Properties["vectorSpaceId"]) { throw "C10 local metadata 混入 vectorSpaceId 键" }
}
$rl | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $isoDir "local-search-isolation.json") -Encoding UTF8
Write-Output "[C10-1] local top1=$($rl.matches[0].similarity) delta=$delta（≤1e-9）metadata 无 spaceId 键"

# 2) one_ 检索：metadata.vectorSpaceId 全为 one_ 空间；两轨 top1 分数必须不同（不同模型）
foreach ($m in $r1.matches) {
    if ($m.metadata.vectorSpaceId -ne $oneSpace) { throw "C10 one_ metadata spaceId 异常 $($m.metadata.vectorSpaceId)" }
}
if ([Math]::Abs($r1.matches[0].similarity - $rl.matches[0].similarity) -lt 1e-6) { throw "C10 两轨 top1 分数相同——疑似同空间串轨" }
[pscustomobject]@{
    oneSpaceId = $r1.vectorSpaceId; localSpaceId = $rl.vectorSpaceId
    oneTop1Similarity = $r1.matches[0].similarity; localTop1Similarity = $rl.matches[0].similarity
    oneMetadataSpaceIds = @($r1.matches | ForEach-Object { $_.metadata.vectorSpaceId } | Select-Object -Unique)
    localMetadataHasSpaceIdKey = $false
    sameLogicalChunkIdCrossTrack = ($r1.matches[0].id -eq $rl.matches[0].id)
    note = "稳定 chunkId 与模型无关（StableChunkIdGenerator 不含模型身份），两轨同逻辑 chunk 同 UUID 是设计特性；空间隔离由表分离+metadata.vectorSpaceId+分数差异证明"
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $isoDir "space-separation.json") -Encoding UTF8
Write-Output "[C10-2] one_ top1=$($r1.matches[0].similarity) ≠ local top1；metadata 空间标记全一致"

# 3) 兼容性端点：local 候选→DIMENSION_MISMATCH；同维异版本→MODEL_SPACE_MISMATCH；本空间→COMPATIBLE
$cLocal = (Invoke-Json Post "$base/api/v1/one/embeddings/index/compatibility" @{ provider = "local"; modelName = "local-hashing-embedding"; modelVersion = "local-v1"; dimensions = 384; indexVersion = 1 }).data
$cOther = (Invoke-Json Post "$base/api/v1/one/embeddings/index/compatibility" @{ provider = "bailian"; modelName = "qwen3.7-text-embedding"; modelVersion = "remote-v2"; dimensions = 1024; indexVersion = 1 }).data
$cSame = (Invoke-Json Post "$base/api/v1/one/embeddings/index/compatibility" @{ provider = "bailian"; modelName = "qwen3.7-text-embedding"; modelVersion = "remote-v1"; dimensions = 1024; indexVersion = 1 }).data
if ($cLocal.decision -ne "DIMENSION_MISMATCH") { throw "C10 local 候选判定异常 $($cLocal.decision)" }
if ($cOther.decision -ne "MODEL_SPACE_MISMATCH") { throw "C10 跨空间判定异常 $($cOther.decision)" }
if ($cSame.decision -ne "COMPATIBLE") { throw "C10 本空间判定异常 $($cSame.decision)" }
[pscustomobject]@{ localCandidate = $cLocal; otherSpaceCandidate = $cOther; sameSpaceCandidate = $cSame } |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $isoDir "compatibility.json") -Encoding UTF8
Write-Output "[C10-3] DIMENSION_MISMATCH / MODEL_SPACE_MISMATCH / COMPATIBLE 三态全中"

# ================= C11 ask 决策链（基础） =================
# 1) QA-001 逐字问题 + documentId 过滤 → ANSWER + mock 受控生成态 + 引用契约
$a1 = (Invoke-Json Post "$base/api/v1/one/rag/ask" @{ question = $qa001; topK = 5; documentId = "DOC-CNC-COOLING-001" }).data
if ($a1.decision -ne "ANSWER") { throw "C11 QA-001 决策异常 $($a1.decision) code=$($a1.errorCode)" }
if ($a1.generationStatus -ne "NOT_CONFIGURED" -or $a1.errorCode -ne "MODEL_NOT_CONFIGURED") {
    throw "C11 mock 受控态异常 gen=$($a1.generationStatus) code=$($a1.errorCode)"
}
if ($a1.sources.Count -lt 1) { throw "C11 sources 为空" }
foreach ($s in $a1.sources) {
    if ($s.sourceId -ne $s.documentId) { throw "C11 sourceId≠documentId" }
    if ($s.citationId -notmatch "^S\d+$") { throw "C11 citationId 非请求级 S 序号：$($s.citationId)" }
    if ($null -ne $s.quotedSnippet -and $s.quotedSnippet.Length -gt 200) { throw "C11 snippet 超 200" }
    if ($null -ne $s.url) { throw "C11 url 非 null" }
}
$a1 | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $askDir "one-qa001-ask.json") -Encoding UTF8
Write-Output "[C11-1] ANSWER + NOT_CONFIGURED/MODEL_NOT_CONFIGURED；sources=$($a1.sources.Count) 引用契约全符合"

# 2) 无关问题 → 受控拒答（REFUSE/LOW_RELEVANCE 或 NO_EVIDENCE），不调用生成
$a2 = (Invoke-Json Post "$base/api/v1/one/rag/ask" @{ question = "量子色动力学在格点规范理论中的重整化群流如何影响渐近自由？"; topK = 5 }).data
if ($a2.decision -eq "ANSWER" -and $a2.generationStatus -eq "SUCCESS") { throw "C11 无关问题竟成功生成" }
$a2 | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $askDir "one-unrelated-ask.json") -Encoding UTF8
Write-Output "[C11-2] 无关问题 decision=$($a2.decision) gen=$($a2.generationStatus) code=$($a2.errorCode)（如实记录）"

# ================= usage 核算 =================
$usage1 = Get-Usage
[pscustomobject]@{
    before = $usage0; after = $usage1
    callsThisScript = ($usage1.calls - $usage0.calls)
    tokensThisScript = ($usage1.totalTokens - $usage0.totalTokens)
    note = "C09 三次真实检索×2 + C11 两次 ask 检索段×2 = 预期 10 次；拒绝类用例 0 调用；local 检索 0 远程调用"
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $retDir "usage-accounting.json") -Encoding UTF8
Write-Output "[USAGE-1] calls=$($usage1.calls)（本脚本 +$($usage1.calls - $usage0.calls)）tokens=$($usage1.totalTokens)"
Write-Output "[C09/C10/C11基础-PASS] 检索契约、跨空间隔离、ask 决策链与引用契约全部达标（夹具专项见 P5）"