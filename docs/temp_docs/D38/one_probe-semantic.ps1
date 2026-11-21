# one_probe-semantic.ps1 —— P0 补充语义 sanity 探针（第 9 次调用，预算内）
# 目的：核实 1024 维向量非退化（分量统计）且语义相关对余弦显著高于无关对。
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$probeDir = Join-Path $repo "docs\evidence\D38\probe"
$key = $env:DASHSCOPE_API_KEY
if ([string]::IsNullOrEmpty($key)) { $key = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','User') }
$base = $env:BAILIAN_EMBEDDING_BASE_URL
if ([string]::IsNullOrEmpty($base)) { $base = [Environment]::GetEnvironmentVariable('BAILIAN_EMBEDDING_BASE_URL','User') }
if ([string]::IsNullOrEmpty($base)) { $base = "https://ws-b5h7p6oc83qc6dn5.cn-beijing.maas.aliyuncs.com/compatible-mode/v1" }
$base = $base.TrimEnd('/')
$texts = @(
    "数控机床冷却液压力不足导致主轴过热报警",
    "冷却系统液压偏低引发温度过高故障",
    "周末去公园散步欣赏秋天的银杏叶"
)
$body = @{ model = "qwen3.7-text-embedding"; input = $texts; encoding_format = "float" } | ConvertTo-Json -Depth 5 -Compress
$resp = Invoke-RestMethod -Method Post -Uri "$base/embeddings" -Headers @{ Authorization = "Bearer $key" } `
    -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
    -TimeoutSec 60 -SkipHttpErrorCheck -StatusCodeVariable status
if ($status -ne 200) {
    $err = ($resp | ConvertTo-Json -Depth 6 -Compress).Replace($key,'***')
    Write-Output "SEMANTIC_PROBE_FAIL status=$status err=$err"; exit 3
}
$embs = @($resp.data | Sort-Object index | ForEach-Object { ,@([double[]]$_.embedding) })
function Cosine([double[]]$a, [double[]]$b) {
    $dot=0.0;$na=0.0;$nb=0.0
    for($i=0;$i -lt $a.Count;$i++){$dot+=$a[$i]*$b[$i];$na+=$a[$i]*$a[$i];$nb+=$b[$i]*$b[$i]}
    return $dot/([Math]::Sqrt($na)*[Math]::Sqrt($nb))
}
$cosAB = Cosine $embs[0] $embs[1]
$cosAC = Cosine $embs[0] $embs[2]
$cosBC = Cosine $embs[1] $embs[2]
$abs = $embs[0] | ForEach-Object { [Math]::Abs($_) }
$stats = $abs | Measure-Object -Minimum -Maximum -Average
$nonZero = ($embs[0] | Where-Object { $_ -ne 0.0 }).Count
$pass = ($cosAB -gt $cosAC) -and ($cosAB -gt $cosBC) -and ($nonZero -gt 900)
$out = [ordered]@{
    probedAt = (Get-Date -Format "yyyy-MM-ddTHH:mm:sszzz")
    purpose = "语义 sanity：相关对余弦应显著高于无关对；分量非退化"
    texts = $texts
    cosAB_related = $cosAB
    cosAC_unrelated = $cosAC
    cosBC_unrelated = $cosBC
    componentStats = @{ minAbs=$stats.Minimum; maxAbs=$stats.Maximum; meanAbs=$stats.Average; nonZeroCount=$nonZero; dims=$embs[0].Count }
    usageTotalTokens = $(if ($resp.usage) { $resp.usage.total_tokens } else { $null })
    verdict = $(if ($pass) { "PASS" } else { "FAIL_DEGENERATE_OR_NON_SEMANTIC" })
}
$out | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $probeDir "semantic-sanity.json") -Encoding UTF8
Write-Output "cosAB=$([Math]::Round($cosAB,6)) cosAC=$([Math]::Round($cosAC,6)) cosBC=$([Math]::Round($cosBC,6)) nonZero=$nonZero/1024 meanAbs=$($stats.Average)"
Write-Output "VERDICT=$($out.verdict) usageTokens=$($out.usageTotalTokens)"
if (-not $pass) { exit 4 }