# one_local-regression.ps1 —— D38 C07：local 轨无回归证明
# 步骤：1) D38 语料经 local 端点入库（零远程调用）2) QA-001 受控探针对比 D37 冻结值
#       3) D37 评测器（只读复用）对 D38 local 完整 3 轮评测 4) 质量投影 diff D37 PGVECTOR 基线
# 判据：探针 Top1 similarity 差 ≤1e-9；diff 质量字段字节一致。评测器 exit=1（诚实 FAIL）不视为回归失败。
param([string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD','User')
$base = "http://127.0.0.1:8080"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$regDir = Join-Path $RepoRoot "docs\evidence\D38\regression"
New-Item -ItemType Directory -Force -Path $regDir | Out-Null

# ---- 1) local 语料入库（D38 模块语料，结构与 D37 逐字一致） ----
$corpus = Get-Content -LiteralPath (Join-Path $RepoRoot "D38\D38_QWE_MROQRE\src\main\resources\knowledge\equipment-fault-corpus.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$indexResults = @()
foreach ($doc in $corpus) {
    $id = $doc.documentId
    Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id" -Method Delete -TimeoutSec 30 | Out-Null
    $payloadChunks = @(); $offset = 0
    for ($i = 0; $i -lt @($doc.chunks).Count; $i++) {
        $c = $doc.chunks[$i]; $len = $c.excerpt.Length
        $payloadChunks += @{ chunkIndex = $i; content = $c.excerpt; globalStart = $offset; globalEnd = $offset + $len }
        $offset += $len
    }
    $req = @{ documentId = $id; version = 1; strategy = "STRUCTURE"; title = $doc.title; chunks = $payloadChunks }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($req | ConvertTo-Json -Depth 6))
    $resp = Invoke-RestMethod -Uri "$base/api/v1/vector-index/index" -Method Post -Body $bytes -ContentType "application/json; charset=utf-8" -TimeoutSec 120
    if ($resp.code -ne 200) { throw "local 入库失败: $id" }
    $indexResults += @{ documentId = $id; status = $resp.data.status; chunkCount = $resp.data.chunkCount; idempotent = $resp.data.idempotent }
    Write-Output "[LOCAL-INDEX] $id status=$($resp.data.status) chunks=$($resp.data.chunkCount)"
}
[System.IO.File]::WriteAllText((Join-Path $regDir "local-index-results.json"), ($indexResults | ConvertTo-Json -Depth 5), $utf8)

# ---- 2) QA-001 受控探针（topK=5/threshold=0.0/documentId 过滤） ----
$frozenTop1 = 0.37974774837493896
$probeReq = @{ query = "数控机床出现主轴过热报警，应该先检查什么？"; topK = 5; threshold = 0.0; documentId = "DOC-CNC-COOLING-001" }
$probeBytes = [System.Text.Encoding]::UTF8.GetBytes(($probeReq | ConvertTo-Json -Depth 5))
$probe = Invoke-RestMethod -Uri "$base/api/v1/retrieval/search" -Method Post -Body $probeBytes -ContentType "application/json; charset=utf-8" -TimeoutSec 30
$top1 = $probe.data.matches[0]
$delta = [Math]::Abs([double]$top1.similarity - $frozenTop1)
$probePass = ($delta -le 1e-9) -and ($probe.data.vectorSpaceId -eq "local|local-hashing-embedding|local-v1|384")
$probeEvidence = [ordered]@{
    questionId = "QA-001"; frozenTop1Similarity = $frozenTop1; actualTop1Similarity = $top1.similarity
    delta = $delta; tolerance = 1e-9; vectorSpaceId = $probe.data.vectorSpaceId
    top1LogicalChunkId = $top1.metadata.chunkContentHash; pass = $probePass; raw = $probe.data
}
[System.IO.File]::WriteAllText((Join-Path $regDir "qa001-local-probe.json"), ($probeEvidence | ConvertTo-Json -Depth 8), $utf8)
Write-Output "[PROBE] top1=$($top1.similarity) frozen=$frozenTop1 delta=$delta pass=$probePass space=$($probe.data.vectorSpaceId)"
if (-not $probePass) { throw "C07 探针超差或空间标识不符——local 轨回归漂移，熔断" }

# ---- 3) D37 评测器只读复用：完整 3 轮评测（local 轨，零远程调用） ----
$jdk = "D:\SoftwareInstall\JDK\JDK21"
$repoM2 = "D:\SoftwareInstall\Apache\repository"
$cp = (@(
    "$repoM2\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar",
    "$repoM2\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar",
    "$repoM2\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
) -join ";")
$evalClasses = Join-Path $RepoRoot "docs\temp_docs\D37\evaluator\classes"
if (-not (Test-Path (Join-Path $evalClasses "d37eval\Evaluator.class"))) { throw "D37 评测器 classes 缺失（只读复用前提）" }
$runsOut = Join-Path $regDir "runs"
New-Item -ItemType Directory -Force -Path $runsOut | Out-Null
# 自测（零成本，先于正式跑）
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "$evalClasses;$cp" d37eval.SelfTest 2>&1 |
    Tee-Object -FilePath (Join-Path $regDir "metrics-selftest.txt")
if ($LASTEXITCODE -ne 0) { throw "D37 评测器自测未全 PASS" }
Write-Output "[OK] 评测器自测全 PASS"
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "$evalClasses;$cp" d37eval.Evaluator `
    --config (Join-Path $RepoRoot "docs\temp_docs\D38\one_local-regression-config.json") `
    --repo $RepoRoot --out $runsOut --rounds 3 --warmup 3 --baseUrl $base 2>&1 |
    Tee-Object -FilePath (Join-Path $regDir "local-evaluation.log")
$evalExit = $LASTEXITCODE
Write-Output "[EVAL-EXIT] $evalExit（0=全PASS/1=有FAIL/2=仅BLOCKED；D37 基线本身含诚实 FAIL，exit=1 属预期）"

# ---- 4) 质量投影 diff：D38 local PGVECTOR 跑 vs D37 冻结 PGVECTOR 基线 ----
$newRun = Get-ChildItem -LiteralPath $runsOut -Directory | Where-Object { $_.Name -like "*PGVECTOR" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $newRun) { throw "未找到 D38 local PGVECTOR 运行目录" }
$d37Baseline = Join-Path $RepoRoot "docs\evidence\D37\baseline\v1.0\PGVECTOR"
$diffReport = Join-Path $regDir "local-vs-d37-quality-diff.txt"
& "$jdk\bin\java.exe" "-Dfile.encoding=UTF-8" -cp "$evalClasses;$cp" d37eval.Evaluator `
    --diff $d37Baseline $newRun.FullName --out $diffReport 2>&1 |
    Tee-Object -FilePath (Join-Path $regDir "local-diff.log")
$diffExit = $LASTEXITCODE
Write-Output "[DIFF-EXIT] $diffExit report=$diffReport"
$diffText = Get-Content -LiteralPath $diffReport -Raw -Encoding UTF8
$identical = ($diffExit -eq 0) -and ($diffText -match "QUALITY_FIELDS_IDENTICAL")
Write-Output "[C07] qualityIdentical=$identical"
if (-not $identical) { throw "C07 质量投影与 D37 基线不一致——local 轨回归漂移，熔断" }
Write-Output "[C07-PASS] local 轨无回归：探针 ≤1e-9 且质量投影字节一致"