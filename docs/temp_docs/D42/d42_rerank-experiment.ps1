# D42 P3 实验臂执行器（A0/A1/A2/A3 之一）。协议=rerank-protocol.json 冻结，禁改参数。
# 流程：停旧实例 → 按臂 env 起实例 → 就绪轮询 → 跑冻结评测器 → 归档证据 → 停实例。
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('A0', 'A1', 'A2', 'A3')]
    [string]$Arm
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo  = 'E:\AI\project\AI-Learning-one\M3-Java'
$mod   = Join-Path $repo 'D42\D42_RERANK'
$jdk   = 'D:\SoftwareInstall\JDK\JDK21'
$mvn   = 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd'
$evCls = Join-Path $repo 'docs\temp_docs\D42\probe\eval-classes'
$cfg   = Join-Path $repo 'docs\temp_docs\D37\evaluator\evaluator-config.json'
$evSrc = Join-Path $repo 'docs\temp_docs\D37\evaluator\src'
$armDir = Join-Path $repo "docs\evidence\D42\rerank\runs\$Arm"
$logDir = Join-Path $repo 'logs\D42'
New-Item -ItemType Directory -Force -Path $armDir, $logDir | Out-Null

# ---- 0) 环境基线（与 r1 同法；密钥仅进程级注入，零落日志） ----
$env:JAVA_HOME = $jdk
$env:AI_MODE = 'mock'
$pgPw = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')
if (-not $pgPw) { $pgPw = $env:PG_VECTOR_PASSWORD }
if (-not $pgPw) { throw 'PG_VECTOR_PASSWORD 不可读' }
$env:PGVECTOR_DB_PASSWORD = $pgPw   # D42 yml 实际占位名（非 PG_PASSWORD）

switch ($Arm) {
    'A0' { $armEnv = @{} }
    'A1' { $armEnv = @{ RERANK_ENABLED = 'true'; RERANK_STRATEGY = 'local' } }
    'A2' { $armEnv = @{ RERANK_ENABLED = 'true'; RERANK_STRATEGY = 'remote'; RERANK_REMOTE_ENABLED = 'true' } }
    'A3' { $armEnv = @{ RERANK_ENABLED = 'true'; RERANK_STRATEGY = 'local'; RERANK_MAX_OUTPUT = '3' } }
}
if ($Arm -eq 'A2') {
    $dk = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY', 'User')
    if (-not $dk) { $dk = $env:DASHSCOPE_API_KEY }
    if (-not $dk) { throw 'DASHSCOPE_API_KEY 不可读（A2 需远程授权）' }
    $armEnv['DASHSCOPE_API_KEY'] = $dk
}

# ---- 1) 停止既有 D42 实例（java fork 命令行含 D42_RERANK；绝不误杀 -Dnacos 进程） ----
$killed = @()
Get-CimInstance Win32_Process | Where-Object {
    $_.Name -in 'java.exe', 'mvn.cmd', 'node.exe' -and
    $_.CommandLine -match 'D42_RERANK' -and $_.CommandLine -notmatch 'nacos'
} | ForEach-Object {
    $killed += $_.ProcessId
    taskkill /T /F /PID $_.ProcessId 2>&1 | Out-Null
}
Start-Sleep -Seconds 3
"STOPPED=$($killed -join ',')"

# ---- 2) 起实例（臂 env 注入子进程环境） ----
foreach ($k in $armEnv.Keys) { Set-Item "Env:$k" $armEnv[$k] }
# 显式清掉其他臂残留
foreach ($k in 'RERANK_ENABLED', 'RERANK_STRATEGY', 'RERANK_REMOTE_ENABLED', 'RERANK_MAX_OUTPUT') {
    if (-not $armEnv.ContainsKey($k)) { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
}
$runLog = Join-Path $logDir "d42_run-$Arm-8080.log"
$runErr = Join-Path $logDir "d42_run-$Arm-8080.log.err"
$proc = Start-Process $mvn -ArgumentList '-B', 'spring-boot:run' -WorkingDirectory $mod `
    -RedirectStandardOutput $runLog -RedirectStandardError $runErr -PassThru -WindowStyle Hidden
"WRAPPER_PID=$($proc.Id)"

# ---- 3) 就绪轮询（180s）----
$ready = $false
$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    try {
        # 就绪判据=HTTP 层可应答（NOSUCH 文档业务码 4001 属正常语义，不作就绪条件）
        $resp = Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/documents/NOSUCH/tables' -TimeoutSec 3 -UseBasicParsing
        if ($resp.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Milliseconds 1500 }
    Start-Sleep -Milliseconds 1500
}
if (-not $ready) { taskkill /T /F /PID $proc.Id 2>&1 | Out-Null; throw "实例 180s 未就绪($Arm)" }
"READY=$Arm"

# ---- 4) 跑冻结评测器（同数据集同参数 D8；rounds3/warmup3/topK5/threshold0） ----
$cp = $evCls
# jackson 依赖：eval-classes 需要的第三方 jar（沿用 D40 时点 classpath 组装法）
$repoM2 = 'D:\SoftwareInstall\Apache\repository'
$jars = @(
    (Get-ChildItem "$repoM2\com\fasterxml\jackson\core\jackson-databind" -Recurse -Filter 'jackson-databind-2.19.0.jar' | Select-Object -First 1).FullName
    (Get-ChildItem "$repoM2\com\fasterxml\jackson\core\jackson-core" -Recurse -Filter 'jackson-core-2.19.0.jar' | Select-Object -First 1).FullName
    (Get-ChildItem "$repoM2\com\fasterxml\jackson\core\jackson-annotations" -Recurse -Filter 'jackson-annotations-2.19.0.jar' | Select-Object -First 1).FullName
) -join ';'
$cp = "$evCls;$jars"
$evLog = Join-Path $armDir 'run-evaluator.log'
& "$jdk\bin\java.exe" '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $cp `
    d37eval.Evaluator --config $cfg --repo $repo --out $armDir `
    --rounds 3 --warmup 3 --baseUrl 'http://127.0.0.1:8080' 2>&1 | Tee-Object -FilePath $evLog | Out-Null
$runExit = $LASTEXITCODE
"RUN_EXIT=$runExit（0/1 均属合法：1=冻结基线已知业务 FAIL）"

# ---- 5) 停实例并归档日志（费用/flags 权威提取点在应用日志） ----
taskkill /T /F /PID $proc.Id 2>&1 | Out-Null
Start-Sleep -Seconds 2
Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -match 'D42_RERANK' -and $_.CommandLine -notmatch 'nacos'
} | ForEach-Object { taskkill /T /F /PID $_.ProcessId 2>&1 | Out-Null }
Copy-Item $runLog (Join-Path $armDir 'app-run.log') -Force
if (Test-Path $runErr) { Copy-Item $runErr (Join-Path $armDir 'app-run.err.log') -Force }

# ---- 6) 臂观测摘要 ----
$appLog = Get-Content (Join-Path $armDir 'app-run.log') -Raw -ErrorAction SilentlyContinue
$summary = [ordered]@{
    arm              = $Arm
    wrapperPid       = $proc.Id
    runExit          = $runExit
    rerankStepLines  = ([regex]::Matches($appLog, 'rerank 步完成')).Count
    flagsLines       = ([regex]::Matches($appLog, 'rerank 步完成.*flags=\[([^\]]*)\]') |
        ForEach-Object { $_.Groups[1].Value }) -join ';'
}
if ($Arm -eq 'A2') {
    $last = [regex]::Matches($appLog, 'cumulativeMeasuredTokens=(\d+), cumulativeCostCny=([\d\.]+)')
    if ($last.Count -gt 0) {
        $m = $last[$last.Count - 1]
        $summary['cumulativeMeasuredTokens'] = [int]$m.Groups[1].Value
        $summary['cumulativeCostCny'] = [double]$m.Groups[2].Value
    }
    $summary['remoteCallLines'] = ([regex]::Matches($appLog, 'rerank\(remote\) 记账')).Count
}
$runSub = Get-ChildItem $armDir -Directory -Filter 'run-*' | Select-Object -First 1
if ($runSub) {
    $raw = Join-Path $runSub.FullName 'raw-results.jsonl'
    $csv = Join-Path $runSub.FullName 'baseline.csv'
    $summary['runDir'] = $runSub.Name
    if (Test-Path $raw) { $summary['rawSha256'] = (Get-FileHash $raw -Algorithm SHA256).Hash.ToLowerInvariant() }
    if (Test-Path $csv) { $summary['baselineSha256'] = (Get-FileHash $csv -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$summary | ConvertTo-Json | Set-Content (Join-Path $armDir 'arm-summary.json') -Encoding utf8
$summary | ConvertTo-Json -Compress
"DONE=$Arm"
