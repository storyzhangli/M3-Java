# D42 P4 异常矩阵 E1-E5 受控在线注入。
# 通道说明（D-EX8 同臂）：E2/E4/E5 使用 baseUrl 指向本地 discard 端口 127.0.0.1:9 的
# 假远程端点 + 显式假 Key（sk-INVALID-LOCAL-ONLY，绝无真实服务可达性），零外部成本零真实计费；
# E1 用空 Key 分支（不发调用）；E3 用空候选分支。
# 每案断言 = ask 响应 rerankFlags/rerankCalls + 应用日志关键行计数，全部落 evidence JSON。
param([switch]$SkipX, [switch]$SkipY, [switch]$SkipZ)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$mod  = Join-Path $repo 'D42\D42_RERANK'
$jdk  = 'D:\SoftwareInstall\JDK\JDK21'
$mvn  = 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd'
$logDir = Join-Path $repo 'logs\D42'
$outDir = Join-Path $repo 'docs\evidence\D42\exceptions'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Stop-D42 {
    Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -match 'D42_RERANK|spring-boot:run' -and $_.CommandLine -notmatch 'nacos' `
        -and $_.Name -in 'java.exe', 'mvn.cmd', 'cmd.exe', 'node.exe'
    } | ForEach-Object { taskkill /T /F /PID $_.ProcessId 2>&1 | Out-Null }
}

function Start-Custom($Name, $EnvMap) {
    Stop-D42 | Out-Null
    Start-Sleep -Seconds 2
    $env:JAVA_HOME = $jdk; $env:AI_MODE = 'mock'
    $pgPw = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User'); if (-not $pgPw) { $pgPw = $env:PG_VECTOR_PASSWORD }
    $env:PGVECTOR_DB_PASSWORD = $pgPw
    foreach ($k in 'RERANK_ENABLED','RERANK_STRATEGY','RERANK_REMOTE_ENABLED','RERANK_MAX_OUTPUT','DASHSCOPE_API_KEY','DASHSCOPE_RERANK_BASE_URL','RERANK_PER_DOC_TOKEN_LIMIT','RERANK_REQUEST_TOKEN_LIMIT','RERANK_BUDGET_MAX_CALLS') { Remove-Item "Env:$k" -EA SilentlyContinue }
    foreach ($k in $EnvMap.Keys) { Set-Item "Env:$k" $EnvMap[$k] }
    $log = Join-Path $logDir "d42_run-EX-$Name.log"
    $p = Start-Process $mvn -ArgumentList '-B','spring-boot:run' -WorkingDirectory $mod -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(220)
    while ((Get-Date) -lt $deadline) {
        try { $r = Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/documents/NOSUCH/tables' -TimeoutSec 3 -UseBasicParsing; if ($r.StatusCode -eq 200) { return @{ pid = $p.Id; log = $log } } } catch { }
        Start-Sleep -Milliseconds 1500
    }
    taskkill /T /F /PID $p.Id 2>&1 | Out-Null
    throw "EX-$Name 实例未就绪"
}

function Ask($q, $topK = 5) {
    $body = @{ question = $q; topK = $topK } | ConvertTo-Json -Compress
    $r = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/rag/ask' -Method Post -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60
    return @{ status = $r.data.retrievalStatus; flags = $r.data.budget.rerankFlags; calls = $r.data.budget.rerankCalls; ms = $r.data.timing.rerankMs }
}

$cases = New-Object System.Collections.Generic.List[object]

function Assert-Case($id, $name, $expectFlags, $expectMaxCalls, $got, $logSig, $logCountMin) {
    $flagOk = $true
    foreach ($ef in $expectFlags) { if ($got.flags -notmatch [regex]::Escape($ef)) { $flagOk = $false } }
    $callsOk = ($got.calls -le $expectMaxCalls)
    $logOk = ((Select-String -Path $logSig.Path -Pattern $logSig.Pattern -EA SilentlyContinue).Count -ge $logSig.Min)
    $cases.Add([ordered]@{
        id = $id; name = $name
        expectFlags = $expectFlags; gotFlags = $got.flags
        expectMaxCalls = $expectMaxCalls; gotCalls = $got.calls
        gotRerankMs = $got.ms; gotStatus = $got.status
        logEvidence = "$($logSig.Pattern) x$((Select-String -Path $logSig.Path -Pattern $logSig.Pattern -EA SilentlyContinue).Count)"
        pass = ($flagOk -and $callsOk -and $logOk)
    })
    "CASE ${id}: flags=$($got.flags) calls=$($got.calls) flagOk=$flagOk callsOk=$callsOk logOk=$logOk"
}

# ================= X 臂：E2 + E4b(TRUNCATED) + E5 =================
if (-not $SkipX) {
    $x = Start-Custom 'X' @{
        RERANK_ENABLED = 'true'; RERANK_STRATEGY = 'remote'; RERANK_REMOTE_ENABLED = 'true'
        DASHSCOPE_API_KEY = 'sk-INVALID-LOCAL-ONLY'
        DASHSCOPE_RERANK_BASE_URL = 'http://127.0.0.1:9/rerank'
        RERANK_PER_DOC_TOKEN_LIMIT = '10'
        RERANK_BUDGET_MAX_CALLS = '1'
    }
    $a1 = Ask 'CNC 冷却系统 E-01 故障怎么处置'
    Assert-Case 'E2+E4b' '瞬时连接失败→单次重试→fail-open；同时 per_doc=10 触发输入截断' @('RETRIED','FAILED_OPEN','TRUNCATED') 9999 $a1 @{ Path = $x.log; Pattern = '单次重试' } 1
    $a2 = Ask '伺服驱动器报警处理流程是什么'
    Assert-Case 'E5' '预算帽 maxCalls=1 触顶→BUDGET_STOP 停发' @('BUDGET_STOP') 2 $a2 @{ Path = $x.log; Pattern = '预算帽触顶' } 1
    Copy-Item $x.log (Join-Path $outDir 'app-run-EX-X.log') -Force
}

# ================= E3：在线通道受限（threshold=0 时检索恒有命中，空候选不可达），
# 以三适配器定向空候选单测为权威证据（NOOP_EMPTY 契约面） =================
$e3Log = Join-Path $outDir 'e3-targeted-tests.log'
$env:JAVA_HOME = $jdk
& $mvn -B -f (Join-Path $repo 'D42\pom.xml') -pl D42_RERANK test '-Dtest=DashScopeRerankAdapterOfflineTest#emptyCandidates,LocalLexicalRerankAdapterTest#emptyCandidates,RerankPortContractTest#noopIdentityAndEmpty' '-DfailIfNoTests=false' 2>&1 | Tee-Object -FilePath $e3Log | Out-Null
$e3Ok = ($LASTEXITCODE -eq 0) -and ((Select-String -Path $e3Log -Pattern 'Tests run: 3, Failures: 0').Count -ge 1)
$cases.Add([ordered]@{
    id = 'E3'; name = '空候选集 NOOP_EMPTY（在线不可达：冻结检索参数 threshold=0 恒有命中；以定向单测为权威）'
    expectFlags = @('NOOP_EMPTY'); gotFlags = 'unit-tested'
    expectMaxCalls = 0; gotCalls = 0
    gotRerankMs = $null; gotStatus = 'OFFLINE_CONTRACT'
    logEvidence = "e3-targeted-tests.log Tests run: 3 Failures: 0 (exit=$LASTEXITCODE)"
    pass = [bool]$e3Ok
})
"E3 targeted unit tests: pass=$e3Ok"

# ================= Y 臂：E4a OVERSIZED =================
if (-not $SkipY) {
    $y = Start-Custom 'Y' @{
        RERANK_ENABLED = 'true'; RERANK_STRATEGY = 'remote'; RERANK_REMOTE_ENABLED = 'true'
        DASHSCOPE_API_KEY = 'sk-INVALID-LOCAL-ONLY'
        DASHSCOPE_RERANK_BASE_URL = 'http://127.0.0.1:9/rerank'
        RERANK_REQUEST_TOKEN_LIMIT = '1'
    }
    $a = Ask 'CNC 冷却系统 E-01 故障怎么处置'
    Assert-Case 'E4a' '请求估算总量超 requestTokenLimit→OVERSIZED 预检拒绝零调用' @('OVERSIZED') 0 $a @{ Path = $y.log; Pattern = '预检回退原序' } 1
    Copy-Item $y.log (Join-Path $outDir 'app-run-EX-Y.log') -Force
}

# ================= Z 臂：E1 缺 Key =================
if (-not $SkipZ) {
    $z = Start-Custom 'Z' @{
        RERANK_ENABLED = 'true'; RERANK_STRATEGY = 'remote'; RERANK_REMOTE_ENABLED = 'true'
        DASHSCOPE_RERANK_BASE_URL = 'http://127.0.0.1:9/rerank'
    }
    $a = Ask 'CNC 冷却系统 E-01 故障怎么处置'
    Assert-Case 'E1' '缺 Key 永久错误 fail-open 零调用（不发网络）' @('FAILED_OPEN') 0 $a @{ Path = $z.log; Pattern = '未注入 DASHSCOPE_API_KEY' } 1
    Copy-Item $z.log (Join-Path $outDir 'app-run-EX-Z.log') -Force
}

Stop-D42 | Out-Null
$allPass = -not ($cases | Where-Object { -not $_.pass })
$matrix = [ordered]@{
    executedAt = (Get-Date).ToString('o')
    channelNote = 'E1-E5 在线受控注入；E2/E4/E5 假端点=本地 discard 端口零外呼；离线单测已另覆盖分类器/预算/截断纯逻辑面（DashScopeRerankAdapterOfflineTest/RerankExceptionClassifierTest）'
    cases = $cases
    allPass = [bool]$allPass
}
$matrix | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outDir 'exceptions-matrix.json') -Encoding utf8
$matrix | ConvertTo-Json -Depth 5 -Compress
"EXCEPTIONS_ALLPASS=$allPass"


