# D38-R02 混合评测执行脚本（#228）
# 用法: pwsh -File hybrid_run-evaluation.ps1 [-RunName runA] [-Rounds 3]
# 前置: 应用已以 HYBRID_ENABLED=true + 最新编译类启动在 8080（脚本不负责起应用；
#       EnvProbe 探测失败会以 BLOCKED 落盘并如实报告，禁止静默降级——熔断#3）
param(
    [string]$RunName = "runA",
    [int]$Rounds = 3
)
$ErrorActionPreference = 'Stop'
$root = 'E:\AI\project\AI-Learning-one\M3-Java'
$ev = "$root\docs\temp_docs\D38\hybrid_evaluator"
$jdk = 'D:\SoftwareInstall\JDK\JDK21\bin'
$mvn = 'D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd'
$repoLocal = 'D:\SoftwareInstall\Apache\repository'

# 1) 应用类必须最新（幂等编译；D38 classes 供 SelfTest/Evaluator import）
& $mvn -q -f "$root\D38\D38_QWE_MROQRE\pom.xml" compile
if ($LASTEXITCODE -ne 0) { Write-Host "MVN_COMPILE_FAIL"; exit 1 }

# 2) hybrideval 编译（幂等）
$cp = "$root\D38\D38_QWE_MROQRE\target\classes;$root\D37\D37_RAG_ESAB\target\classes;" +
    "$repoLocal\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar;" +
    "$repoLocal\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar;" +
    "$repoLocal\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
New-Item -ItemType Directory -Force -Path "$ev\out" | Out-Null
$srcs = Get-ChildItem "$ev\src\hybrideval\*.java" | ForEach-Object FullName
& "$jdk\javac.exe" -encoding UTF-8 -cp $cp -d "$ev\out" $srcs
if ($LASTEXITCODE -ne 0) { Write-Host "JAVAC_FAIL"; exit 1 }

# 3) SelfTest 门禁（必须先过，C01/C15）
$cp2 = "$ev\out;$cp;$repoLocal\org\slf4j\slf4j-api\2.0.17\slf4j-api-2.0.17.jar"
$outDir = "$root\docs\evidence\D38\hybrid\evaluator"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
& "$jdk\java.exe" "-Dfile.encoding=UTF-8" -cp $cp2 hybrideval.SelfTest *> "$outDir\selftest.log"
if ($LASTEXITCODE -ne 0) { Write-Host "SELFTEST_FAIL"; exit 1 }
Write-Host "[OK] SelfTest PASS，进入正式评测（应用必须已在 8080 运行且 hybrid.enabled=true）"

# 4) 正式评测（独立 run 目录不覆盖；BLOCKED 时 Evaluator 自行落盘并返回码 2）
$runs = "$root\docs\evidence\D38\hybrid\runs\$RunName"
New-Item -ItemType Directory -Force -Path $runs | Out-Null
& "$jdk\java.exe" "-Dfile.encoding=UTF-8" -cp $cp2 hybrideval.Evaluator `
    --config "$ev\hybrid-evaluator-config.json" --repo $root --out $runs --rounds $Rounds `
    *> "$runs\evaluator.log"
$code = $LASTEXITCODE
Write-Host "EVALUATOR_EXIT=$code"
Get-Content "$runs\evaluator.log" -Tail 20
exit $code
