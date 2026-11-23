# ============================================================================
# D40-R01 P6：C05 默认关闭零差异回归（d40_c05-regression.ps1，基于 d39_c05-regression.ps1）
# 前置：C19 夹具已清理（对齐 D39 口径）、默认实例 r5（无 ACCESS_ENABLED/无注入）。
# 动作：匿名探针 → 评测器源码只读编译 → SelfTest 全绿 → 完整评测 3 轮 → 与 D37 冻结
#       PGVECTOR 基线质量投影逐字节 diff → QA-001 top1 锚点 delta≤1e-9。
# 熔断：diff≠0 → BASELINE_DRIFT exit 3。零远程（local 轨、AI_MODE=mock）。
# ============================================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$jdk = 'D:\SoftwareInstall\JDK\JDK21\bin'
$evSrc = Join-Path $repo 'docs\temp_docs\D37\evaluator\src'
$outClasses = Join-Path $repo 'docs\temp_docs\D40\regression\classes'
$regDir = Join-Path $repo 'docs\evidence\D40\regression'
$baseline = Join-Path $repo 'docs\evidence\D37\baseline\v1.0\PGVECTOR'
$cfg = Join-Path $repo 'docs\temp_docs\D37\evaluator\evaluator-config.json'
$mavenRepo = 'D:\SoftwareInstall\Apache\repository'
$cp = "$mavenRepo\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar;$mavenRepo\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar;$mavenRepo\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar;$repo\D37\D37_RAG_ESAB\target\classes;$outClasses"
New-Item -ItemType Directory -Force -Path $outClasses, $regDir | Out-Null

# 0) 默认态行为面探针：匿名检索必须 200+有结果（零差异旁证；无 token 即 D40 门禁 disabled）
$probe = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/retrieval/search' -Method Post -ContentType 'application/json; charset=utf-8' `
    -Body ([Text.Encoding]::UTF8.GetBytes('{"query":"主轴过热","topK":5,"similarityThreshold":0}')) -TimeoutSec 30
Write-Output "STEP0_ANON code=$($probe.code) total=$($probe.data.total) top1=$($probe.data.matches[0].chunkId)"
if ($probe.code -ne 200 -or $probe.data.total -lt 1) { Write-Output 'VERDICT=DISABLED_PATH_BROKEN'; exit 1 }

# 1) 编译评测器（源码只读）
$srcs = Get-ChildItem "$evSrc\d37eval" -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\javac.exe" -encoding UTF-8 -cp $cp -d $outClasses @srcs
Write-Output "STEP1_COMPILE_EXIT=$LASTEXITCODE"
if ($LASTEXITCODE -ne 0) { exit 1 }

# 2) SelfTest 全绿门禁
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
if ($runExit -ne 0 -and $runExit -ne 1) { exit 1 }
if (Select-String -Path (Join-Path $regDir 'run-evaluator.log') -Pattern 'REPEAT_RESULT_DIVERGED' -Quiet) {
    Write-Output 'VERDICT=INTRA_RUN_ROUND_DIVERGED_CIRCUIT_BREAK'; exit 3
}

# 4) 与 D37 冻结基线质量投影逐字节 diff
$runDir = Get-ChildItem $regDir -Directory -Filter 'run-*' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Write-Output "STEP3_RUN_DIR=$($runDir.FullName)"
& "$jdk\java.exe" '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $cp `
    d37eval.Evaluator --diff $baseline $runDir.FullName `
    --out (Join-Path $regDir 'diff-vs-d37-frozen.txt')
$diffExit = $LASTEXITCODE
Write-Output "STEP4_DIFF_EXIT=$diffExit"
Write-Output ("STEP4_DIFF_HEAD=" + ((Get-Content (Join-Path $regDir 'diff-vs-d37-frozen.txt') -TotalCount 12 -Encoding UTF8) -join ' | '))
if ($diffExit -ne 0) { Write-Output 'VERDICT=BASELINE_DRIFT_CIRCUIT_BREAK'; exit 3 }

# 5) QA-001 锚点：top1 chunkId/similarity 与冻结值对照
$qLine = Get-Content (Join-Path $repo 'docs\evidence\D37\dataset\rag-evaluation-questions.v1.0.jsonl') -TotalCount 1 -Encoding UTF8
$qJson = $qLine | ConvertFrom-Json
$body = '{"query":' + ($qJson.question | ConvertTo-Json -Compress) + ',"topK":5,"similarityThreshold":0}'
$resp = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/retrieval/search' -Method Post `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 15
$top1Id = $resp.data.matches[0].chunkId
$top1 = [double]$resp.data.matches[0].similarity
$frozen = 0.37974774837493896
$delta = [Math]::Abs($top1 - $frozen)
Write-Output "STEP5_QA001_TOP1_ID=$top1Id top1=$top1 delta=$delta"
if ($top1Id -ne '3b1501c0-6a99-535a-a787-991f877024c8' -or $delta -gt 1e-9) { Write-Output 'VERDICT=BASELINE_DRIFT_CIRCUIT_BREAK'; exit 3 }

$result = [ordered]@{
    gate = 'C05_D40_ZERO_DIFF_REGRESSION'
    finishedAt = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    instance = 'ACCESS_ENABLED 缺省(false) FAULT_INJECTION 缺省(false) AI_MODE=mock（默认零差异态）'
    precondition = 'C19 夹具已清理（对齐 D39 先清理后回归口径）'
    anonProbe = @{ code = $probe.code; total = $probe.data.total; top1 = $probe.data.matches[0].chunkId }
    selftestExit = $stExit; runExit = $runExit; diffExitVsD37 = $diffExit
    # R03 整改：退出码语义显式化（Evaluator.java L648-651 源码契约）
    runExitNote = 'runExit=1 ⇔ 数据集业务判定含 FAIL 行（fail>0 即返回 1，属 D37 冻结基线既有业务事实，QA-001 REFUSAL_FALSE_NEGATIVE，D38/D39 同口径先例）。非执行失败：执行失败/环境异常路径为 exit 2（无 PASS）与 REPEAT_RESULT_DIVERGED 分支（脚本已单独检测并熔断 exit 3）。本门禁判定链=SelfTest 75/75 → 三轮间零发散 → diffExit=0（质量投影 60/60 逐字节）→ QA-001 锚点 delta≤1e-9；diffExit=0 仅证明与冻结基线一致，其成立前提已含"runExit 属预期语义集合 {0,1}"，两者互证而非互相替代。'
    runExitContractSource = 'docs/temp_docs/D37/evaluator/src/d37eval/Evaluator.java L648-651（if (fail>0) return 1; return pass>0?0:2）'
    qa001Top1Id = $top1Id; qa001Top1 = $top1; qa001Delta = $delta; qa001Tolerance = 1e-9
    runDir = $runDir.Name; remoteCalls = '0（local 轨）'
    verdict = 'PASS'
}
$result | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $regDir 'regression-result.json') -Encoding UTF8
Write-Output 'VERDICT=PASS_C05_ZERO_DIFF'
exit 0
