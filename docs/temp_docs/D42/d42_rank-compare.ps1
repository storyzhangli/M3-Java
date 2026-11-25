# D42 rank 指标跨臂对照（协议 rankMetricFormulas 同源实现）
# A0 顺序源=baseline.csv returnedLogicalIds（评测器 PG 直连序=rerank 缺席原序）
# 目标臂顺序源=rank-observations-{Arm}.jsonl（app 日志观测行，D-EX6 ask 通道）
# 口径统一：按题去重（每题取首个正式轮，验证各轮一致后仅计一次），真值=expectedChunkIds 集合。
param(
    [string]$Target = 'A1',
    [switch]$SkipRoundCheck
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$dsFile = Join-Path $repo 'docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl'
$questions = Get-Content $dsFile | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }
$truthByQ = @{}
foreach ($q in $questions) { $truthByQ[$q.questionId] = @($q.expectedChunkIds) }

function Metrics($orderById) {   # questionId -> ordered ids array
    $mrr = 0.0; $hit1 = 0; $ndcg = 0.0; $frr = New-Object System.Collections.Generic.List[double]; $n = 0
    foreach ($qid in $orderById.Keys) {
        $truth = $truthByQ[$qid]
        $ids = @($orderById[$qid])
        $rel = @($ids | ForEach-Object { if ($truth -contains $_) { 1 } else { 0 } })
        $firstIdx = [array]::IndexOf($rel, 1)
        if ($firstIdx -ge 0 -and $firstIdx -lt 5) { $mrr += 1.0 / ($firstIdx + 1) }
        if ($rel.Count -gt 0 -and $rel[0] -eq 1) { $hit1 += 1 }
        if ($firstIdx -ge 0) { $frr.Add($firstIdx + 1) }
        $dcg = 0.0
        for ($i = 0; $i -lt [Math]::Min(5, $rel.Count); $i++) { if ($rel[$i] -eq 1) { $dcg += 1 / [Math]::Log($i + 2, 2) } }
        $ideal = [Math]::Min(5, @($truth).Count)
        $idcg = 0.0
        for ($i = 0; $i -lt $ideal; $i++) { $idcg += 1 / [Math]::Log($i + 2, 2) }
        if ($idcg -gt 0) { $ndcg += $dcg / $idcg }
        $n++
    }
    [ordered]@{
        n = $n
        'MRR@5' = [math]::Round($mrr / $n, 6)
        'nDCG@5' = [math]::Round($ndcg / $n, 6)
        'hit@1' = [math]::Round($hit1 / $n, 6)
        meanFirstRelevantRank = if ($frr.Count) { [math]::Round(($frr | Measure-Object -Average).Average, 4) } else { $null }
    }
}

# ---- A0：每题一行，顺序即向量原序 ----
$a0csv = Get-ChildItem (Join-Path $repo 'docs\evidence\D42\rerank\runs\A0') -Recurse -Filter 'baseline.csv' | Select-Object -First 1
$rows0 = Import-Csv $a0csv.FullName
$ordA0 = [ordered]@{}
foreach ($r in $rows0) { $ordA0[$r.questionId] = @($r.returnedLogicalIds -split ';' | Where-Object { $_ }) }
$m0 = Metrics $ordA0

# ---- 目标臂：obs 按题去重 + 轮间一致性 ----
$obsF = Join-Path $repo "docs\evidence\D42\rerank\runs\$Target\rank-observations-$Target.jsonl"
$rowsT = Get-Content $obsF | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }
$byQ = $rowsT | Group-Object questionId
$ordT = [ordered]@{}
$roundDiff = 0
foreach ($g in $byQ) {
    $seqs = $g.Group | ForEach-Object { $_.orderedLogicalIds } | Sort-Object -Unique
    if ($seqs.Count -gt 1) { $roundDiff++ }
    $ordT[$g.Name] = @(($g.Group | Select-Object -First 1).orderedLogicalIds -split ';' | Where-Object { $_ })
}
$mt = Metrics $ordT

$delta = [ordered]@{
    target = $Target
    'dMRR@5'    = [math]::Round($mt.'MRR@5' - $m0.'MRR@5', 6)
    'dnDCG@5'   = [math]::Round($mt.'nDCG@5' - $m0.'nDCG@5', 6)
    'dhit@1'    = [math]::Round($mt.'hit@1' - $m0.'hit@1', 6)
    dMeanFRR   = if ($mt.meanFirstRelevantRank -ne $null -and $m0.meanFirstRelevantRank -ne $null) { [math]::Round($mt.meanFirstRelevantRank - $m0.meanFirstRelevantRank, 4) } else { $null }
}
$out = [ordered]@{ A0 = $m0; $Target = $mt; delta = $delta; targetRoundInconsistentQuestions = $roundDiff; note = 'A0 顺序源=baseline.csv(PG直连原序)；目标臂=app ask 日志观测序；按题去重' }
$outDir = Join-Path $repo 'docs\evidence\D42\rerank\runs\compare'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outDir "rank-compare-A0-$Target.json") -Encoding utf8
$out | ConvertTo-Json -Depth 5 -Compress
