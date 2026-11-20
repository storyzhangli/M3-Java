# D37 评测一条命令入口：编译 → 自测（必须先全 PASS）→ 正式评测
# 用法:
#   & run-evaluation.ps1 -BaseUrl "http://127.0.0.1:8080" -DatasetVersion "v1.0" -Rounds 3 -Warmup 3
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$DatasetVersion = "v1.0",
    [int]$Rounds = 3,
    [int]$Warmup = 3,
    [string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java",
    [string]$OutRoot = ""
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_TOOL_OPTIONS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

if ($DatasetVersion -ne "v1.0") { throw "本脚本仅支持数据集 v1.0（冻结版本），传入: $DatasetVersion" }
if ($Rounds -lt 3) { throw "正式轮次必须 >= 3" }
if ($OutRoot -eq "") { $OutRoot = Join-Path $RepoRoot "docs\evidence\D37\runs" }

$jdk = "D:\SoftwareInstall\JDK\JDK21"
$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"
$repoM2 = "D:\SoftwareInstall\Apache\repository"
$jackson = @(
    "$repoM2\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar",
    "$repoM2\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar",
    "$repoM2\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
)
foreach ($j in $jackson) { if (-not (Test-Path -LiteralPath $j)) { throw "缺少 Jackson jar: $j" } }
$cp = ($jackson -join ";")

$evalDir = Join-Path $RepoRoot "docs\temp_docs\D37\evaluator"
$classes = Join-Path $evalDir "classes"
$logDir = Join-Path $RepoRoot "logs\D37"
New-Item -ItemType Directory -Force -Path $classes, $logDir, $OutRoot | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"

# 1) 编译（评测器源码 + 生产 UrlSafetyPolicy 原文件只读编译，不修改）
$sources = Get-ChildItem -LiteralPath (Join-Path $evalDir "src\d37eval") -Filter *.java | ForEach-Object { $_.FullName }
$sources += Join-Path $RepoRoot "D37\D37_RAG_ESAB\src\main\java\com\zl\d37\rag\citation\UrlSafetyPolicy.java"
$compileLog = Join-Path $logDir "compile-$stamp.log"
& $javac -encoding UTF-8 -cp $cp -d $classes @sources 2>&1 | Tee-Object -FilePath $compileLog
if ($LASTEXITCODE -ne 0) { throw "评测器编译失败，见 $compileLog" }
Write-Output "[OK] 编译通过"

# 2) 自测（C15：先于正式评测，全 PASS 才继续）
$selftestFile = Join-Path $RepoRoot "docs\evidence\D37\metrics-selftest.txt"
& $java "-Dfile.encoding=UTF-8" -cp "$classes;$cp" d37eval.SelfTest 2>&1 | Tee-Object -FilePath $selftestFile
if ($LASTEXITCODE -ne 0) { throw "自测未全 PASS，禁止正式评测。见 $selftestFile" }
Write-Output "[OK] 自测全 PASS（证据: $selftestFile）"

# 3) 正式评测
$runLog = Join-Path $logDir "evaluation-$stamp.log"
& $java "-Dfile.encoding=UTF-8" -cp "$classes;$cp" d37eval.Evaluator `
    --config (Join-Path $evalDir "evaluator-config.json") `
    --repo $RepoRoot --out $OutRoot --rounds $Rounds --warmup $Warmup --baseUrl $BaseUrl 2>&1 |
    Tee-Object -FilePath $runLog
$code = $LASTEXITCODE
Write-Output "[EXIT] $code（0=全PASS / 1=有FAIL / 2=仅BLOCKED或NOT RUN）；日志: $runLog"
exit $code
