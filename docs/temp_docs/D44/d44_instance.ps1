# D44_instance.ps1 — D44 应用实例生命周期（start/stop，AI_MODE=mock，端口 8080）
# 改编自 d42_instance.ps1（去实验臂，保留 env 通道与就绪判据；H9：密钥仅 User 级环境变量读取）
param(
    [Parameter(Mandatory = $true)][ValidateSet('start', 'stop')][string]$Action,
    [switch]$Fault
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$mod  = Join-Path $repo 'D44\D44_RAG_PDAM'
$jdk  = 'D:\SoftwareInstall\JDK\JDK21'
$mvn  = 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd'
$logDir = Join-Path $repo 'logs\D44'
$pidFile = Join-Path $repo 'docs\temp_docs\D44\instance.pid'

function Stop-D44 {
    Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -match 'D44_RAG_PDAM|spring-boot:run' -and $_.CommandLine -notmatch 'nacos' `
        -and $_.Name -in 'java.exe', 'mvn.cmd', 'cmd.exe'
    } | ForEach-Object { taskkill /T /F /PID $_.ProcessId 2>&1 | Out-Null }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    'STOPPED'
}

if ($Action -eq 'stop') { Stop-D44; return }

$env:JAVA_HOME = $jdk
$env:AI_MODE = 'mock'
$pgPw = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')
if (-not $pgPw) { $pgPw = $env:PG_VECTOR_PASSWORD }
$env:PGVECTOR_DB_PASSWORD = $pgPw
# 回归默认态：清掉一切可能残留的启用开关（H3：矩阵启用另行 env 注入，本脚本=baseline 态）
foreach ($k in 'ACCESS_ENABLED','HYBRID_ENABLED','RERANK_ENABLED','RERANK_STRATEGY',
               'ONE_EMBEDDING_ENABLED','TABLE_OCR_ENABLED','SYNC_RETRY_ENABLED','DASHSCOPE_API_KEY',
               'FAULT_INJECTION_ENABLED','FAULT_INJECTION_ADD_ATTEMPT','FAULT_INJECTION_DELETE_ATTEMPT') {
    Remove-Item "Env:$k" -ErrorAction SilentlyContinue
}
if ($Fault) {
    # A4 检索域受控注入：第 1 次 add 抛 SIMULATED_PG_CONNECTION_LOST，重试收敛
    $env:FAULT_INJECTION_ENABLED = 'true'
    $env:FAULT_INJECTION_ADD_ATTEMPT = '1'
}
Stop-D44 | Out-Null
Start-Sleep -Seconds 2
$runLog = Join-Path $logDir 'D44_run-8080.log'
$proc = Start-Process $mvn -ArgumentList '-B', 'spring-boot:run' -WorkingDirectory $mod `
    -RedirectStandardOutput $runLog -RedirectStandardError "$runLog.err" -PassThru -WindowStyle Hidden
Set-Content $pidFile $proc.Id
"WRAPPER_PID=$($proc.Id)"
$deadline = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $deadline) {
    try {
        $resp = Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/documents/NOSUCH/tables' -TimeoutSec 3 -UseBasicParsing
        if ($resp.StatusCode -eq 200) { 'READY'; exit 0 }
    } catch { }
    Start-Sleep -Milliseconds 1500
}
taskkill /T /F /PID $proc.Id 2>&1 | Out-Null
throw '实例 240s 未就绪'

