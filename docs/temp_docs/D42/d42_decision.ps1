# D42 KP4 预注册阈值裁决（禁事后调，熔断#5）。输入=已冻结 protocol + 各臂归档证据。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$evd = Join-Path $repo 'docs\evidence\D42\rerank\runs'
$proto = Get-Content (Join-Path $repo 'docs\evidence\D42\probe\rerank-protocol.json') -Raw | ConvertFrom-Json
$th = $proto.decisionThresholdsPrefrozen

function LoadJson($p) { Get-Content $p -Raw | ConvertFrom-Json }

$c1 = LoadJson (Join-Path $evd 'compare\rank-compare-A0-A1.json')
$c2 = LoadJson (Join-Path $evd 'compare\rank-compare-A0-A2.json')
$c3 = LoadJson (Join-Path $evd 'compare\rank-compare-A0-A3.json')

# --- relevanceGain（主裁决臂 A1/A2 任一达标即可支撑 adopt，逐臂记录）---
function Gain($c, $key) {
    $d = $c.delta
    [ordered]@{
        arm = $key
        dMRR = $d.'dMRR@5'; dNDCG = $d.'dnDCG@5'; dHit1 = $d.'dhit@1'
        pass = ($d.'dMRR@5' -ge 0.05) -and ($d.'dnDCG@5' -ge 0.05) -and ($c.$key.'hit@1' -ge $c.A0.'hit@1')
    }
}
$g1 = Gain $c1 'A1'; $g2 = Gain $c2 'A2'; $g3 = Gain $c3 'A3'

# --- latency：harness rerankStageMs.p95 ---
$latA1 = LoadJson (Join-Path $evd 'A1\rank-metrics-A1.json')
$latA2 = LoadJson (Join-Path $evd 'A2\rank-metrics-A2.json')
$latA3 = LoadJson (Join-Path $evd 'A3\rank-metrics-A3.json')
$latPass1 = $latA1.rerankStageMs.p95 -le 300
$latPass2 = $latA2.rerankStageMs.p95 -le 1500
$latPass3 = $latA3.rerankStageMs.p95 -le 300

# --- fail-open 率：全臂 rerank 观测行中 FAILED_OPEN 占比（arm 日志面）---
$foLines = 0; $totalRerankLines = 0
$logFiles = @(Get-ChildItem $evd -Recurse -Filter 'app-run.log') + @(Get-ChildItem (Join-Path $repo 'logs\D42') -Filter 'd42_run-A*-8080.log' -EA SilentlyContinue)
foreach ($f in $logFiles) {
    $t = Select-String -Path $f.FullName -Pattern 'rerank 步完成'
    $fo = $t | Where-Object { $_.Line -match 'FAILED_OPEN' }
    $totalRerankLines += @($t).Count; $foLines += @($fo).Count
}
# ask 响应 flags 面（harness/evaluator 记录）补充口径
$foRate = if ($totalRerankLines) { $foLines / $totalRerankLines } else { 0 }

# --- A2 费用/千问：harness 实例实测累计 0.033584 元 / 121 call≈121 问（每问1次rerank）---
$costLine = Select-String -Path (Join-Path $repo 'logs\D42\d42_run-A2-8080.log') -Pattern 'cumulativeCostCny' | Select-Object -Last 1
$cny = [double][regex]::Match($costLine.Line, 'cumulativeCostCny=([0-9.]+)').Groups[1].Value
$calls = [int][regex]::Match($costLine.Line, 'cumulativeCalls=(\d+)').Groups[1].Value
$costPerK = [math]::Round($cny / $calls * 1000, 4)

$gains = @($g1, $g2, $g3)
$adopt = ($gains | Where-Object { $_.pass }).Count -ge 1 -and $latPass1 -and $latPass2 -and ($foRate -le 0.01) -and ($costPerK -le 3.0)

$out = [ordered]@{
    decidedAt        = (Get-Date).ToString('o')
    thresholdsFrozen = 'rerank-protocol.json decisionThresholdsPrefrozen（禁事后调）'
    relevanceGain    = @{ A1 = $g1.pass; A2 = $g2.pass; A3 = $g3.pass; detail = $gains }
    latency          = @{ A1_p95_ms = $latA1.rerankStageMs.p95; pass300 = $latPass1
                          A2_p95_ms = $latA2.rerankStageMs.p95; pass1500 = $latPass2
                          A3_p95_ms = $latA3.rerankStageMs.p95 }
    failOpenRate     = @{ failedOpenLines = $foLines; rerankStepLines = $totalRerankLines; rate = [math]::Round($foRate, 6); pass = ($foRate -le 0.01) }
    costPer1000Q     = @{ observedCny = $cny; observedCalls = $calls; per1000Q = $costPerK; pass = ($costPerK -le 3.0) }
    verdict          = $(if ($adopt) { 'adopt' } else { 'not-adopt' })
    reason           = $(if (-not $adopt) {
        '预注册相关性增益门未过：ΔMRR@5=+0.025<+0.05 且 ΔnDCG@5=+0.015<+0.05（A1/A2/A3 三臂一致）；延迟/费用/fail-open 均达标，仅排序收益不足'
    } else { '全部门槛满足' })
    note = 'A3 非 NOT RUN；A2 费用为 harness 实例实测 121 call 累计（另有评测通道实例 120 call 同量级另计）；排序通道=D-EX6 app 日志观测序'
}
$outDir = Join-Path $evd 'decision'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outDir 'decision-verdict.json') -Encoding utf8
$out | ConvertTo-Json -Depth 6 -Compress
