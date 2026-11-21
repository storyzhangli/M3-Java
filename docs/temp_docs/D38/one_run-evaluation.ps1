# one_run-evaluation.ps1 — D38 P5/P6：one_ 正式评测一键运行
# 编译 + SelfTest 全绿门禁 + 两次独立运行（各 warmup3 + 正式3轮）+ 质量投影 diff。
# 预算：ONE_CALL_BUDGET=1000（用户 2026-11-21 裁决，全规格 warmup=3），会话级注入不写系统环境变量。
# 每问每轮 4 次真实调用（search 2 + ask 2）；探测与 canary 首检复用；预计每跑 484 次。
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$env:JAVA_HOME = "D:\SoftwareInstall\JDK\JDK21"
$env:JAVA_TOOL_OPTIONS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
$env:ONE_CALL_BUDGET = "1000"
$env:ONE_TOKEN_BUDGET = "200000"
$env:AI_MODE = "mock"
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$ev = Join-Path $repo "docs\temp_docs\D38\one_evaluator"
$runsRoot = Join-Path $repo "docs\evidence\D38\runs"
New-Item -ItemType Directory -Force -Path $runsRoot | Out-Null
$jk = "D:\SoftwareInstall\Apache\repository\com\fasterxml\jackson\core"
$cp = @(
    "$ev\classes",
    "$jk\jackson-core\2.19.0\jackson-core-2.19.0.jar",
    "$jk\jackson-databind\2.19.0\jackson-databind-2.19.0.jar",
    "$jk\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar",
    "$repo\D38\D38_QWE_MROQRE\target\classes"
) -join ";"

# 1) 编译
New-Item -ItemType Directory -Force -Path "$ev\classes" | Out-Null
$srcs = Get-ChildItem -LiteralPath "$ev\src\oneeval" -Filter *.java | ForEach-Object { $_.FullName }
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -cp $cp -d "$ev\classes" @srcs
if ($LASTEXITCODE -ne 0) { throw "oneeval 编译失败" }
Write-Output "[OK] oneeval 编译通过"

# 2) SelfTest 门禁（全绿才允许正式跑）
& "$env:JAVA_HOME\bin\java.exe" -cp $cp oneeval.SelfTest 2>&1 | Tee-Object -FilePath (Join-Path $runsRoot "selftest.log")
if ($LASTEXITCODE -ne 0) { throw "SelfTest 未全绿，禁止正式评测" }
Write-Output "[OK] SelfTest 全绿"

# 3) 两次独立运行
$cfg = Join-Path $ev "one-evaluator-config.json"
$runDirs = @()
foreach ($i in 1..2) {
    $log = Join-Path $runsRoot "one-evaluation-run$i.log"
    Write-Output "[RUN$i] 开始（warmup3 + 正式3轮，预计 484 次真实调用）..."
    & "$env:JAVA_HOME\bin\java.exe" -cp $cp oneeval.Evaluator `
        --config $cfg --repo $repo --out $runsRoot --rounds 3 --warmup 3 `
        --baseUrl "http://127.0.0.1:8080" 2>&1 | Tee-Object -FilePath $log
    $exit = $LASTEXITCODE
    $logText = Get-Content -LiteralPath $log -Raw -Encoding UTF8
    if ($logText -match "FATAL") { throw "[RUN$i] 熔断终止（见 $log）：REMOTE_NON_DETERMINISM / BUDGET_EXCEEDED / 轮间分歧" }
    if ($exit -notin @(0, 1, 2)) { throw "[RUN$i] 异常退出码 $exit" }
    $doneLine = ($logText -split "`n" | Where-Object { $_ -match "\[DONE\] runDir=" }) | Select-Object -Last 1
    if ($doneLine -match "runDir=(\S+) exitCode=") { $runDirs += $Matches[1] } else { throw "[RUN$i] 未找到 runDir" }
    Write-Output "[RUN$i] exit=$exit（0=全PASS/1=有诚实FAIL/2=仅BLOCKED）runDir=$($runDirs[-1])"
}

# 4) 质量投影 diff（逐字节一致才允许冻结）
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$diffTxt = Join-Path $runsRoot "consistency-diff-$stamp.txt"
& "$env:JAVA_HOME\bin\java.exe" -cp $cp oneeval.Evaluator --diff $runDirs[0] $runDirs[1] --out $diffTxt
$diffExit = $LASTEXITCODE
$diffText = Get-Content -LiteralPath $diffTxt -Raw -Encoding UTF8
$identical = ($diffExit -eq 0) -and ($diffText -match "QUALITY_FIELDS_IDENTICAL")
[pscustomobject]@{
    runA = $runDirs[0]; runB = $runDirs[1]
    identical = $identical
    diffExit = $diffExit
    rawReport = $diffTxt
    checkedAt = (Get-Date).ToString("o")
    note = "质量投影排除 similarity/计时浮点；排序+逻辑ID+决策+标签+状态逐字节比对"
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $runsRoot "consistency-diff-$stamp.json") -Encoding UTF8
Write-Output "[DIFF] identical=$identical report=$diffTxt"
if (-not $identical) { throw "REPEAT_RESULT_DIVERGED：两次运行质量投影分歧，不得冻结基线（分歧明细见报告）" }
Write-Output "[C12-PASS] 3轮×2跑质量投影字节一致，可进入 P6 冻结"