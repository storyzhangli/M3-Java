# one_probe-embedding.ps1 —— D38 P0 探测门禁 G1–G5（阿里百炼 OpenAI 兼容 /embeddings）
# 契约：密钥只经环境变量读取，任何输出/落盘不含密钥本体；错误体脱敏后截断 ≤500 字符。
# 用法：pwsh -File one_probe-embedding.ps1 [-Model <name>] [-MaxCalls 9]
param(
    [string]$Model = "qwen3.7-text-embedding",
    [int]$MaxCalls = 9
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$probeDir = Join-Path $repo "docs\evidence\D38\probe"
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null

# ---- 密钥与地址（只读环境变量，不回显） ----
$key = $env:DASHSCOPE_API_KEY
if ([string]::IsNullOrEmpty($key)) { $key = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY','User') }
if ([string]::IsNullOrEmpty($key)) { Write-Output "GATE_FAIL=API_KEY_MISSING"; exit 2 }
$base = $env:BAILIAN_EMBEDDING_BASE_URL
if ([string]::IsNullOrEmpty($base)) { $base = [Environment]::GetEnvironmentVariable('BAILIAN_EMBEDDING_BASE_URL','User') }
if ([string]::IsNullOrEmpty($base)) { $base = "https://ws-b5h7p6oc83qc6dn5.cn-beijing.maas.aliyuncs.com/compatible-mode/v1" }
$base = $base.TrimEnd('/')
$defaultBase = "https://ws-b5h7p6oc83qc6dn5.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
if ($base -ne $defaultBase) { Write-Output "WARN=BASE_URL_OVERRIDE（环境变量指向非任务书默认地址，请显式确认）: $base" }

$script:calls = 0
$script:tokens = 0
$script:usageUnavailable = 0
$log = [System.Collections.Generic.List[string]]::new()
function Add-Log([string]$m) { $script:log.Add("[$(Get-Date -Format 'HH:mm:ss')] $m"); Write-Output $m }
function Sanitize([string]$s) {
    if ($null -eq $s) { return "" }
    $s = $s.Replace($key, "***API_KEY***")
    $s = [regex]::Replace($s, 'Bearer\s+\S+', 'Bearer ***')
    $s = [regex]::Replace($s, 'sk-[A-Za-z0-9]{16,}', 'sk-***')
    if ($s.Length -gt 500) { $s = $s.Substring(0,500) + "...(truncated)" }
    return $s
}

# ---- 单次 embeddings 调用（返回结构化结果，不抛异常） ----
function Invoke-Embed([string[]]$Texts, [string]$Tag) {
    if ($script:calls -ge $MaxCalls) { return @{ ok=$false; gate="BUDGET_EXCEEDED"; status=0; error="call budget reached" } }
    $script:calls++
    $bodyObj = @{ model = $Model; input = $Texts; encoding_format = "float" }
    $bodyJson = $bodyObj | ConvertTo-Json -Depth 5 -Compress
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyJson)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$base/embeddings" `
            -Headers @{ Authorization = "Bearer $key" } `
            -ContentType "application/json; charset=utf-8" `
            -Body $bodyBytes -TimeoutSec 60 -SkipHttpErrorCheck -StatusCodeVariable status
        $sw.Stop()
        if ($status -eq 200 -and $resp.data -and $resp.data.Count -gt 0 -and $resp.data[0].embedding) {
            $usage = $null
            if ($resp.PSObject.Properties.Name -contains 'usage' -and $resp.usage) { $usage = $resp.usage }
            if ($usage -and $usage.PSObject.Properties.Name -contains 'total_tokens') { $script:tokens += [long]$usage.total_tokens }
            else { $script:usageUnavailable++ }
            $emb = @($resp.data[0].embedding)
            Add-Log "$Tag OK status=200 n=$($Texts.Count) dims=$($emb.Count) usageTotal=$(if($usage){$usage.total_tokens}else{'UNAVAILABLE'}) ms=$($sw.ElapsedMilliseconds)"
            return @{ ok=$true; status=200; embeddings=@($resp.data | ForEach-Object { ,@($_.embedding) }); dims=$emb.Count; usage=$usage; modelEcho=$resp.model; ms=$sw.ElapsedMilliseconds }
        } else {
            $errRaw = ($resp | ConvertTo-Json -Depth 6 -Compress)
            $err = Sanitize $errRaw
            Add-Log "$Tag FAIL status=$status error=$err ms=$($sw.ElapsedMilliseconds)"
            $code = ""
            if ($resp.PSObject.Properties.Name -contains 'error' -and $resp.error) {
                if ($resp.error.PSObject.Properties.Name -contains 'code') { $code = [string]$resp.error.code }
                elseif ($resp.error.PSObject.Properties.Name -contains 'type') { $code = [string]$resp.error.type }
            }
            return @{ ok=$false; status=$status; error=$err; errorCode=$code; ms=$sw.ElapsedMilliseconds }
        }
    } catch {
        $sw.Stop()
        $err = Sanitize $_.Exception.Message
        Add-Log "$Tag NETWORK_FAIL error=$err ms=$($sw.ElapsedMilliseconds)"
        return @{ ok=$false; status=0; error=$err; errorCode="NETWORK"; ms=$sw.ElapsedMilliseconds }
    }
}

function Cosine([double[]]$a, [double[]]$b) {
    $dot=0.0; $na=0.0; $nb=0.0
    for ($i=0; $i -lt $a.Count; $i++) { $dot += $a[$i]*$b[$i]; $na += $a[$i]*$a[$i]; $nb += $b[$i]*$b[$i] }
    if ($na -eq 0 -or $nb -eq 0) { return 0.0 }
    return $dot / ([Math]::Sqrt($na)*[Math]::Sqrt($nb))
}
function L2Norm([double[]]$a) { $s=0.0; foreach($x in $a){$s+=$x*$x}; return [Math]::Sqrt($s) }

$result = [ordered]@{
    probedAt = (Get-Date -Format "yyyy-MM-ddTHH:mm:sszzz")
    baseUrlHost = ([Uri]$base).Host
    baseUrlPath = ([Uri]$base).AbsolutePath
    modelRequested = $Model
    gates = [ordered]@{}
    callsUsed = 0; tokensUsed = 0; usageUnavailableCalls = 0
}

# ---- G1：端点可达 + 模型有效 + 密钥有效（兼 G2 维度 / G4 归一化） ----
Add-Log "G1 START model=$Model base=$base keyLength=$($key.Length)"
$g1 = Invoke-Embed -Texts @("你好") -Tag "G1"
if (-not $g1.ok) {
    $gate = switch ($g1.status) {
        0 { "ENDPOINT_UNREACHABLE" }
        401 { "AUTH_FAILED" }
        403 { "AUTH_FAILED" }
        404 { "MODEL_NOT_FOUND" }
        default {
            if ($g1.error -match 'model' -and ($g1.status -eq 400)) { "MODEL_NOT_FOUND_OR_INVALID" } else { "G1_FAILED_$($g1.status)" }
        }
    }
    $result.gates.G1 = @{ status="FAIL"; gate=$gate; httpStatus=$g1.status; errorCode=$g1.errorCode; error=$g1.error }
    $result.callsUsed=$script:calls; $result.tokensUsed=$script:tokens; $result.usageUnavailableCalls=$script:usageUnavailable
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $probeDir "probe-result.json") -Encoding UTF8
    $log | Set-Content -LiteralPath (Join-Path $probeDir "probe-log.txt") -Encoding UTF8
    Write-Output "GATE_FAIL=$gate（硬停止：禁止改名重试，请核对控制台模型列表/密钥/网络）"
    exit 3
}
$dims = [int]$g1.dims
$norm = L2Norm ([double[]]$g1.embeddings[0])
$result.gates.G1 = @{ status="PASS"; httpStatus=200; modelEcho=$g1.modelEcho; ms=$g1.ms }
Add-Log "G1 PASS dims=$dims L2norm=$([Math]::Round($norm,6))"

# ---- G2：维度 ≤2000（HNSW vector 上限） ----
if ($dims -le 0) {
    $result.gates.G2 = @{ status="FAIL"; gate="INVALID_DIMENSION"; dims=$dims }
} elseif ($dims -gt 2000) {
    $result.gates.G2 = @{ status="FAIL"; gate="DIMENSION_EXCEEDS_HNSW"; dims=$dims; note="需探测 dimensions 降维参数或改 halfvec/换模型，本轮硬停止" }
} else {
    $result.gates.G2 = @{ status="PASS"; dims=$dims }
}
if ($result.gates.G2.status -eq "FAIL") {
    $result.callsUsed=$script:calls; $result.tokensUsed=$script:tokens; $result.usageUnavailableCalls=$script:usageUnavailable
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $probeDir "probe-result.json") -Encoding UTF8
    $log | Set-Content -LiteralPath (Join-Path $probeDir "probe-log.txt") -Encoding UTF8
    Write-Output "GATE_FAIL=$($result.gates.G2.gate) dims=$dims"
    exit 4
}
Add-Log "G2 PASS dims=$dims <= 2000"

# ---- G4：归一化（不阻断） ----
$normalized = [Math]::Abs($norm - 1.0) -le 1e-3
$result.gates.G4 = @{ status=$(if($normalized){"PASS"}else{"RECORDED"}); l2norm=$norm; normalized=$normalized; note=$(if($normalized){"输出已归一化，COSINE 口径与 local 轨一致"}else{"NOT_NORMALIZED：仍用 COSINE_DISTANCE（余弦对模长不敏感），文档明示"}) }
Add-Log "G4 $(if($normalized){'PASS'}else{'RECORDED NOT_NORMALIZED'}) norm=$norm"

# ---- G3：批量上限（2/8/10/16/25，首个 4xx 即停，B=最后成功值） ----
$batchSizes = @(2,8,10,16,25)
$maxBatch = 1
$batchProbe = @()
foreach ($n in $batchSizes) {
    if ($script:calls -ge $MaxCalls - 2) { Add-Log "G3 STOP（预留 G5 canary 2 次调用）"; break }
    $texts = @(); for ($i=0; $i -lt $n; $i++) { $texts += "你好" }
    $r = Invoke-Embed -Texts $texts -Tag "G3-batch$n"
    $batchProbe += @{ batch=$n; ok=$r.ok; httpStatus=$r.status; errorCode=$r.errorCode }
    if ($r.ok) { $maxBatch = $n } else { break }
}
if ($maxBatch -eq 1) {
    # 2 都失败时验证单条（G1 已证明单条可用，无需再调用）
    Add-Log "G3 FALLBACK maxBatch=1（G1 单条已验证）"
}
$result.gates.G3 = @{ status="PASS"; maxBatch=$maxBatch; probes=$batchProbe; note="单条 Token 上限未在探测中触达，以官方文档/运行时 4xx 为准" }
Add-Log "G3 PASS maxBatch=$maxBatch"

# ---- G5：确定性 canary（同文本 2 次，余弦 ≥1−1e-6） ----
$c1 = Invoke-Embed -Texts @("数控机床冷却液压力异常排查") -Tag "G5-canary1"
$c2 = Invoke-Embed -Texts @("数控机床冷却液压力异常排查") -Tag "G5-canary2"
if ($c1.ok -and $c2.ok) {
    $cos = Cosine ([double[]]$c1.embeddings[0]) ([double[]]$c2.embeddings[0])
    $det = $cos -ge (1.0 - 1e-6)
    $result.gates.G5 = @{ status=$(if($det){"PASS"}else{"FAIL"}); canaryCosine=$cos; deterministic=$det; gate=$(if($det){"OK"}else{"REMOTE_NON_DETERMINISM"}) }
    Add-Log "G5 $(if($det){'PASS'}else{'FAIL REMOTE_NON_DETERMINISM'}) cosine=$cos"
} else {
    $result.gates.G5 = @{ status="FAIL"; gate="CANARY_CALL_FAILED"; error=$(if(-not $c1.ok){$c1.error}else{$c2.error}) }
    Add-Log "G5 FAIL canary 调用失败"
}

$result.callsUsed=$script:calls; $result.tokensUsed=$script:tokens; $result.usageUnavailableCalls=$script:usageUnavailable
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $probeDir "probe-result.json") -Encoding UTF8
$log | Set-Content -LiteralPath (Join-Path $probeDir "probe-log.txt") -Encoding UTF8

# ---- 冻结参数（G1/G2 通过才生成） ----
$params = [ordered]@{
    model = $Model
    modelVersion = $(if ($g1.modelEcho) { $g1.modelEcho } else { "unknown-server-echo" })
    dimensions = $dims
    maxBatch = $maxBatch
    maxInputTokens = $null
    maxInputTokensNote = "探测未触达单条上限，待官方文档/运行时核验"
    normalized = $normalized
    l2norm = $norm
    canaryCosine = $(if ($result.gates.G5.ContainsKey('canaryCosine')) { $result.gates.G5.canaryCosine } else { $null })
    deterministic = $(if ($result.gates.G5.ContainsKey('deterministic')) { $result.gates.G5.deterministic } else { $false })
    distanceMetric = "COSINE"
    baseUrlHost = ([Uri]$base).Host
    baseUrlEnvVar = "BAILIAN_EMBEDDING_BASE_URL"
    apiKeyEnvVar = "DASHSCOPE_API_KEY"
    probedAt = $result.probedAt
    probeCalls = $script:calls
    probeTokens = $script:tokens
}
$params | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $probeDir "one-embedding-params.json") -Encoding UTF8
Add-Log "FROZEN one-embedding-params.json dims=$dims maxBatch=$maxBatch normalized=$normalized"
Write-Output "PROBE_DONE calls=$($script:calls) tokens=$($script:tokens) usageUnavailable=$($script:usageUnavailable)"
if ($result.gates.G5.status -eq "FAIL") { exit 5 }
exit 0