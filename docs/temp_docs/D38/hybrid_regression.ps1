# ============================================================================
# D38-R02 P3：基线回归门禁（hybrid_regression.ps1）
# 任务书 §2 P3：D37 评测器（源码只读复用）指向 D38 local 端点完整跑 1 次，
#   质量字段与 D37 冻结 PGVECTOR 基线逐字节 diff；QA-001 Top1 similarity 差 ≤1e-9。
# 不一致 → BASELINE_DRIFT 熔断（退出码 3），禁止在漂移基线上做混合对比。
# 全程 local 轨：零远程调用（DeepSeek 0 / 百炼 0）。
# ============================================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$mavenRepo = 'D:\SoftwareInstall\Apache\repository'
$jdk = 'D:\SoftwareInstall\JDK\JDK21\bin'
$evSrc = Join-Path $repo 'docs\temp_docs\D37\evaluator\src'
$outClasses = Join-Path $repo 'docs\temp_docs\D38\hybrid_eval_out\d37classes'
$regDir = Join-Path $repo 'docs\evidence\D38\hybrid\regression'
$baseline = Join-Path $repo 'docs\evidence\D37\baseline\v1.0\PGVECTOR'
$cfg = Join-Path $repo 'docs\temp_docs\D37\evaluator\evaluator-config.json'
$cp = "$mavenRepo\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar;$mavenRepo\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar;$mavenRepo\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar;$repo\D37\D37_RAG_ESAB\target\classes;$outClasses"

New-Item -ItemType Directory -Force -Path $outClasses, $regDir | Out-Null

# 1) 编译（评测器源码只读；输出目录独立）
$srcs = Get-ChildItem "$evSrc\d37eval" -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\javac.exe" -encoding UTF-8 -cp $cp -d $outClasses @srcs
Write-Output "STEP1_COMPILE_EXIT=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

# 2) SelfTest 全绿门禁（75 项）
& "$jdk\java.exe" '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $cp d37eval.SelfTest |
    Tee-Object -FilePath (Join-Path $regDir 'selftest.log')
$stExit = $LASTEXITCODE
Write-Output "STEP2_SELFTEST_EXIT=$stExit"
if ($stExit -ne 0) { exit 1 }

# 3) 完整评测跑 1 次（20 问 ×3 轮 + warmup 3，local 轨）
& "$jdk\java.exe" '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $cp `
    d37eval.Evaluator --config $cfg --repo $repo --out $regDir `
    --rounds 3 --warmup 3 --baseUrl http://127.0.0.1:8080 |
    Tee-Object -FilePath (Join-Path $regDir 'run-evaluator.log')
$runExit = $LASTEXITCODE
Write-Output "STEP3_RUN_EXIT=$runExit"
# 退出码语义（任务书 P4.4）：0=全PASS 1=有FAIL（冻结基线本就含 FAIL 行，如 QA-001
# REFUSAL_FALSE_NEGATIVE，属业务事实非漂移）2=仅BLOCKED。仅 BLOCKED/其他视为异常。
if ($runExit -ne 0 -and $runExit -ne 1) { exit 1 }
if (Select-String -Path (Join-Path $regDir 'run-evaluator.log') -Pattern 'REPEAT_RESULT_DIVERGED' -Quiet) {
    Write-Output 'VERDICT=INTRA_RUN_ROUND_DIVERGED_CIRCUIT_BREAK'; exit 3
}

# 定位新 run 目录（最新 run-*）
$runDir = Get-ChildItem $regDir -Directory -Filter 'run-*' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Output "STEP3_RUN_DIR=$($runDir.FullName)"

# 4) 与 D37 冻结基线质量投影逐字节 diff
& "$jdk\java.exe" '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $cp `
    d37eval.Evaluator --diff $baseline.FullName $runDir.FullName `
    --out (Join-Path $regDir 'diff-vs-d37-frozen.txt')
$diffExit = $LASTEXITCODE
Write-Output "STEP4_DIFF_EXIT=$diffExit"
Write-Output ("STEP4_DIFF_REPORT=" + (Get-Content (Join-Path $regDir 'diff-vs-d37-frozen.txt') -Raw -Encoding UTF8))
if ($diffExit -ne 0) { Write-Output 'VERDICT=BASELINE_DRIFT_CIRCUIT_BREAK'; exit 3 }

# 5) QA-001 原文探针：Top1 similarity 与冻结值差 ≤1e-9
$qLine = Get-Content (Join-Path $repo 'docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl') -TotalCount 1 -Encoding UTF8
$qJson = $qLine | ConvertFrom-Json
$body = '{"query":' + ($qJson.question | ConvertTo-Json -Compress) + ',"topK":5,"threshold":0}'
$resp = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/retrieval/search' -Method Post `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 15
$top1 = [double]$resp.data.matches[0].similarity
$frozen = 0.37974774837493896
$delta = [Math]::Abs($top1 - $frozen)
Write-Output "STEP5_QA001_TOP1=$top1"
Write-Output "STEP5_QA001_DELTA=$delta"
if ($delta -gt 1e-9) { Write-Output 'VERDICT=BASELINE_DRIFT_CIRCUIT_BREAK'; exit 3 }

$result = [ordered]@{
    gate              = 'C05_P3_REGRESSION'
    finishedAt        = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    compileExit       = 0
    selftestExit      = $stExit
    runExit           = $runExit
    diffExitVsD37     = $diffExit
    diffReport        = 'diff-vs-d37-frozen.txt (QUALITY_FIELDS_IDENTICAL expected)'
    qa001Top1         = $top1
    qa001Frozen       = $frozen
    qa001Delta        = $delta
    qa001Tolerance    = 1e-9
    runDir            = $runDir.Name
    remoteCalls       = '0（local 轨，结构性零远程）'
    verdict           = 'PASS'
}
$result | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $regDir 'regression-result.json') -Encoding UTF8
Write-Output 'VERDICT=PASS_C05_BASELINE_REGRESSION'
exit 0
