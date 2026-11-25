# D42 P3 rank harness（D-EX6 排序通道）：对已运行实例串行 POST /api/v1/rag/ask
# （warmup3+rounds3×20题），从应用日志解析 rerank 观测顺序 → uuid→logical 映射
# （A0 evaluator 产 chunk-id-mapping.json）→ 按协议公式离线复算排序敏感指标。
# A0 顺序输入=baseline.csv returnedLogicalIds（PGVECTOR 直连序=rerank 缺席原序，等价论证见协议）。
param(
    [Parameter(Mandatory = $true)][ValidateSet('A1', 'A2', 'A3')][string]$Arm,
    [int]$Warmup = 3,
    [int]$Rounds = 3
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$dsFile = Join-Path $repo 'docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl'
$mapFile = Get-ChildItem (Join-Path $repo 'docs\evidence\D42\rerank\runs\A0') -Recurse -Filter 'chunk-id-mapping.json' | Select-Object -First 1
if (-not $mapFile) { throw 'A0 chunk-id-mapping.json 缺失（uuid→logical 权威映射源）' }
$appLog = Join-Path $repo "logs\D42\d42_run-$Arm-8080.log"
$runDir = Join-Path $repo "docs\evidence\D42\rerank\runs\$Arm"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

# uuid → logical id 映射（键可能带命名空间前缀，做规范化）
$uuidMap = @{}
$map = Get-Content $mapFile.FullName -Raw | ConvertFrom-Json
foreach ($pr in $map.PSObject.Properties) { $uuidMap[$pr.Name.ToLowerInvariant()] = $pr.Value }

$questions = Get-Content $dsFile | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }
"QUESTIONS=$($questions.Count) WARMUP=$Warmup ROUNDS=$Rounds"

$obsFile = Join-Path $runDir "rank-observations-$Arm.jsonl"
New-Item -ItemType Directory -Force -Path (Split-Path $obsFile) | Out-Null
Set-Content $obsFile '' -Encoding utf8

function Invoke-Ask($q) {
    # 注意：body 不可带 strategy——该字段是 chunk metadata 等值过滤（SimilaritySearchService L154
    # eq("strategy",…)），夹具值为 STRUCTURE，传检索策略名 PURE_VECTOR 会滤空全部命中（D-EX7）。
    $body = @{ question = $q.question; topK = 5 } | ConvertTo-Json -Compress
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/rag/ask' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
    $sw.Stop()
    return @{ resp = $resp; e2eMs = $sw.Elapsed.TotalMilliseconds }
}

for ($r = 1; $r -le ($Warmup + $Rounds); $r++) {
    $isFormal = $r -gt $Warmup
    foreach ($q in $questions) {
        $call = Invoke-Ask $q
        if (-not $isFormal) { continue }   # warmup 轮不入统计（协议 latencyProtocol）
        # 从应用日志尾部取本轮该题的 rerank order 观测行（harness 为唯一流量源，串行安全）
        $orderLine = (Get-Content $appLog -Tail 80 | Where-Object { $_ -match 'rerank order 观测: orderedChunkIds=(.*)' } | Select-Object -Last 1)
        $orderedIds = @()
        if ($orderLine) {
            $raw = [regex]::Match($orderLine, 'orderedChunkIds=(.*)').Groups[1].Value.Trim()
            if ($raw) {
                $orderedIds = $raw.Split(',') | ForEach-Object {
                    $k = $_.ToLowerInvariant()
                    if ($uuidMap.ContainsKey($k)) { $uuidMap[$k] } else { "UNMAPPED:$_" }
                }
            }
        }
        $rec = [ordered]@{
            questionId     = $q.questionId
            round          = $r
            retrievalStatus = $call.resp.data.retrievalStatus
            orderedLogicalIds = ($orderedIds -join ';')
            rerankMs       = $call.resp.data.timing.rerankMs
            e2eMs          = [math]::Round($call.e2eMs, 3)
            rerankFlags    = $call.resp.data.budget.rerankFlags
            rerankCalls    = $call.resp.data.budget.rerankCalls
        }
        Add-Content $obsFile ($rec | ConvertTo-Json -Compress -Depth 4) -Encoding utf8
    }
    "ROUND $r/$($Warmup + $Rounds) done"
}

# ---- 指标复算（协议 rankMetricFormulas 逐式实现）----
$rows = Get-Content $obsFile | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }
$truthByQ = @{}
foreach ($q in $questions) { $truthByQ[$q.questionId] = @($q.expectedChunkIds) }

$mrr = 0.0; $hit1 = 0; $ndcgSum = 0.0; $frrList = New-Object System.Collections.Generic.List[double]
$n = 0
foreach ($row in $rows) {
    $truth = $truthByQ[$row.questionId]
    $ids = @()
    if ($row.orderedLogicalIds) { $ids = @($row.orderedLogicalIds -split ';' | Where-Object { $_ }) }
    $rel = @($ids | ForEach-Object { if ($truth -contains $_) { 1 } else { 0 } })
    $firstIdx = [array]::IndexOf($rel, 1)
    $mrr += $(if ($firstIdx -ge 0 -and $firstIdx -lt 5) { 1.0 / ($firstIdx + 1) } else { 0.0 })
    $hit1 += $(if ($rel.Count -gt 0 -and $rel[0] -eq 1) { 1 } else { 0 })
    if ($firstIdx -ge 0) { $frrList.Add($firstIdx + 1) }
    # nDCG@5 二值
    $dcg = 0.0
    for ($i = 0; $i -lt [Math]::Min(5, $rel.Count); $i++) {
        if ($rel[$i] -eq 1) { $dcg += 1 / [Math]::Log($i + 2, 2) }
    }
    $idealOnes = [Math]::Min(5, @($truth).Count)
    $idcg = 0.0
    for ($i = 0; $i -lt $idealOnes; $i++) { $idcg += 1 / [Math]::Log($i + 2, 2) }
    $ndcgSum += $(if ($idcg -gt 0) { $dcg / $idcg } else { 0.0 })
    $n++
}
$rerankMsArr = @($rows | ForEach-Object { [double]$_.rerankMs }) | Sort-Object
$e2eArr = @($rows | ForEach-Object { [double]$_.e2eMs }) | Sort-Object
function P95($a) { if (-not $a -or $a.Count -eq 0) { return $null }; $idx = [Math]::Ceiling(0.95 * $a.Count); $a[[Math]::Min($idx, $a.Count) - 1] }
function P50($a) { if (-not $a -or $a.Count -eq 0) { return $null }; $a[[Math]::Ceiling($a.Count / 2) - 1] }

$metrics = [ordered]@{
    arm = $Arm
    n = $n
    'MRR@5' = [math]::Round($mrr / $n, 6)
    'nDCG@5' = [math]::Round($ndcgSum / $n, 6)
    'hit@1' = [math]::Round($hit1 / $n, 6)
    firstRelevantRank = [ordered]@{
        note = '1-based，@5 内；无相关命中题不入分母'
        counted = $frrList.Count
        mean = if ($frrList.Count) { [math]::Round(($frrList | Measure-Object -Average).Average, 4) } else { $null }
    }
    rerankStageMs = [ordered]@{ p50 = P50 $rerankMsArr; p95 = P95 $rerankMsArr; max = ($rerankMsArr | Measure-Object -Maximum).Maximum }
    e2eMs = [ordered]@{ p50 = P50 $e2eArr; p95 = P95 $e2eArr; max = ($e2eArr | Measure-Object -Maximum).Maximum }
}
$metrics | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runDir "rank-metrics-$Arm.json") -Encoding utf8
$metrics | ConvertTo-Json -Depth 5 -Compress
"HARNESS_DONE=$Arm"
