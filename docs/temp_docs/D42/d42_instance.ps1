# D42 实验实例生命周期：start -Arm X / stop。env 注入与就绪判据同 d42_rerank-experiment.ps1。
param(
    [Parameter(Mandatory = $true)][ValidateSet('start', 'stop')][string]$Action,
    [ValidateSet('A0', 'A1', 'A2', 'A3')][string]$Arm = 'A0'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$mod  = Join-Path $repo 'D42\D42_RERANK'
$jdk  = 'D:\SoftwareInstall\JDK\JDK21'
$mvn  = 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd'
$logDir = Join-Path $repo 'logs\D42'
$pidFile = Join-Path $repo 'docs\temp_docs\D42\instance.pid'

function Stop-D42 {
    Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -match 'D42_RERANK|spring-boot:run' -and $_.CommandLine -notmatch 'nacos' `
        -and $_.Name -in 'java.exe', 'mvn.cmd', 'cmd.exe', 'node.exe'
    } | ForEach-Object { taskkill /T /F /PID $_.ProcessId 2>&1 | Out-Null }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    'STOPPED'
}

if ($Action -eq 'stop') { Stop-D42; return }

$env:JAVA_HOME = $jdk
$env:AI_MODE = 'mock'
$pgPw = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')
if (-not $pgPw) { $pgPw = $env:PG_VECTOR_PASSWORD }
$env:PGVECTOR_DB_PASSWORD = $pgPw
foreach ($k in 'RERANK_ENABLED', 'RERANK_STRATEGY', 'RERANK_REMOTE_ENABLED', 'RERANK_MAX_OUTPUT', 'DASHSCOPE_API_KEY') {
    Remove-Item "Env:$k" -ErrorAction SilentlyContinue
}
switch ($Arm) {
    'A0' { }
    'A1' { $env:RERANK_ENABLED = 'true'; $env:RERANK_STRATEGY = 'local' }
    'A2' {
        $env:RERANK_ENABLED = 'true'; $env:RERANK_STRATEGY = 'remote'; $env:RERANK_REMOTE_ENABLED = 'true'
        $dk = [Environment]::GetEnvironmentVariable('DASHSCOPE_API_KEY', 'User')
        if (-not $dk) { $dk = $env:DASHSCOPE_API_KEY }
        if (-not $dk) { throw 'DASHSCOPE_API_KEY 不可读' }
        $env:DASHSCOPE_API_KEY = $dk
    }
    'A3' { $env:RERANK_ENABLED = 'true'; $env:RERANK_STRATEGY = 'local'; $env:RERANK_MAX_OUTPUT = '3' }
}
Stop-D42 | Out-Null
Start-Sleep -Seconds 2
$runLog = Join-Path $logDir "d42_run-$Arm-8080.log"
$proc = Start-Process $mvn -ArgumentList '-B', 'spring-boot:run' -WorkingDirectory $mod `
    -RedirectStandardOutput $runLog -RedirectStandardError "$runLog.err" -PassThru -WindowStyle Hidden
Set-Content $pidFile $proc.Id
"WRAPPER_PID=$($proc.Id) ARM=$Arm"
$deadline = (Get-Date).AddSeconds(200)
while ((Get-Date) -lt $deadline) {
    try {
        $resp = Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/documents/NOSUCH/tables' -TimeoutSec 3 -UseBasicParsing
        if ($resp.StatusCode -eq 200) { "READY=$Arm"; exit 0 }
    } catch { }
    Start-Sleep -Milliseconds 1500
}
taskkill /T /F /PID $proc.Id 2>&1 | Out-Null
throw "实例 200s 未就绪($Arm)"
