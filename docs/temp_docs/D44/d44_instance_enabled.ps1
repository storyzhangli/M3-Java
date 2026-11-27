# d44_instance_enabled.ps1 — enabled 态实例生命周期（矩阵专用；baseline 脚本会清开关故另立）
# start 参数：-Access（必填 true）-One（one_ 轨臂，可选）；ONE 臂强制假 baseUrl=零外网（预算 externalCalls=0）
param(
    [Parameter(Mandatory = $true)][ValidateSet('start', 'stop')][string]$Action,
    [switch]$One
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
# 矩阵态：清实验残留但注入 ACCESS（+可选 ONE），ONE 臂外网指向黑洞端口=零真实外呼
foreach ($k in 'HYBRID_ENABLED', 'RERANK_ENABLED', 'RERANK_STRATEGY', 'TABLE_OCR_ENABLED', 'DASHSCOPE_API_KEY') {
    Remove-Item "Env:$k" -ErrorAction SilentlyContinue
}
$env:ACCESS_ENABLED = 'true'
if ($One) {
    $env:ONE_EMBEDDING_ENABLED = 'true'
    $env:BAILIAN_EMBEDDING_BASE_URL = 'http://127.0.0.1:9'
    # OneEmbeddingProperties.validate fail-fast 要求注入 key；黑洞 baseUrl=零真实外呼，哑值仅过断言（禁用于任何真实调用）
    $env:DASHSCOPE_API_KEY = 'sk-d44-matrix-dummy-zero-egress'
    $env:ONE_EMBEDDING_DIMENSIONS = '1024'
} else {
    Remove-Item 'Env:ONE_EMBEDDING_ENABLED' -ErrorAction SilentlyContinue
    Remove-Item 'Env:ONE_EMBEDDING_DIMENSIONS' -ErrorAction SilentlyContinue
    Remove-Item 'Env:DASHSCOPE_API_KEY' -ErrorAction SilentlyContinue
}
# D44 H3：hybrid expected 锚=env 注入 D44 KP2 新 ID 面（yml 默认 10fc69d5 前代锚不改，本轮矩阵 hybrid 面需要）
$kp2 = Get-Content 'E:\AI\project\AI-Learning-one\M3-Java\docs\evidence\D44\probe\kp2-frozen.json' -Raw | ConvertFrom-Json
$env:HYBRID_EXPECTED_FINGERPRINT = $kp2.idAnchor
"HYBRID-ANCHOR-INJECTED=$($env:HYBRID_EXPECTED_FINGERPRINT.Substring(0,8))…"
Stop-D44 | Out-Null
Start-Sleep -Seconds 2
$runLog = Join-Path $logDir 'd44_run-8080.log'
$proc = Start-Process $mvn -ArgumentList '-B', 'spring-boot:run' -WorkingDirectory $mod `
    -RedirectStandardOutput $runLog -RedirectStandardError "$runLog.err" -PassThru -WindowStyle Hidden
Set-Content $pidFile $proc.Id
"WRAPPER_PID=$($proc.Id)"
$deadline = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $deadline) {
    try {
        $resp = Invoke-WebRequest 'http://127.0.0.1:8080/api/v1/documents/NOSUCH/tables' -TimeoutSec 3 -UseBasicParsing
        if ($resp.StatusCode -eq 200) { 'READY(enabled' + $(if ($One) { '+ONE' }) + ')'; exit 0 }
    } catch { }
    Start-Sleep -Milliseconds 1500
}
taskkill /T /F /PID $proc.Id 2>&1 | Out-Null
throw '实例 240s 未就绪'

