# d39_cleanup-verify.ps1 — 编译+运行 D39CleanupVerify（-Apply 才执行 DB 级夹具 DELETE）
param([switch]$Apply)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = 'E:\AI\project\AI-Learning-one\M3-Java\docs\temp_docs\D39\cleanup'
$repo = 'D:\SoftwareInstall\Apache\repository'
$jdk  = 'D:\SoftwareInstall\JDK\JDK21\bin'
$cpItems = @(
  (Get-ChildItem -Recurse -Path $repo -Filter 'postgresql-42.7.11.jar' | Select-Object -First 1).FullName,
  (Get-ChildItem -Recurse -Path $repo -Filter 'mysql-connector-j-8.0.33.jar' | Select-Object -First 1).FullName,
  (Get-ChildItem -Recurse -Path $repo -Filter 'jackson-core-2.19.0.jar' | Select-Object -First 1).FullName,
  (Get-ChildItem -Recurse -Path $repo -Filter 'jackson-databind-2.19.0.jar' | Select-Object -First 1).FullName,
  (Get-ChildItem -Recurse -Path $repo -Filter 'jackson-annotations-2.19.0.jar' | Select-Object -First 1).FullName
)
$cp = ($cpItems -join ';')
New-Item -ItemType Directory -Force -Path "$root\classes" | Out-Null
& "$jdk\javac.exe" -encoding UTF-8 -cp $cp -d "$root\classes" "$root\tools\D39CleanupVerify.java"
if ($LASTEXITCODE -ne 0) { throw "COMPILE_FAIL" }
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD','User')
$env:D39_PG_PW = $env:PG_VECTOR_PASSWORD; $env:D39_MY_PW = '123456'
$outFile = 'E:\AI\project\AI-Learning-one\M3-Java\docs\evidence\D39\cleanup\cleanup-verify.txt'
New-Item -ItemType Directory -Force -Path (Split-Path $outFile) | Out-Null
$tag = if ($Apply) { 'APPLY' } else { 'VERIFY' }
"=== $tag $(Get-Date -Format o) ===" | Add-Content $outFile -Encoding utf8
if ($Apply) {
    & "$jdk\java.exe" -cp "$cp;$root\classes" D39CleanupVerify apply 2>&1 | Tee-Object -FilePath $outFile -Append
} else {
    & "$jdk\java.exe" -cp "$cp;$root\classes" D39CleanupVerify 2>&1 | Tee-Object -FilePath $outFile -Append
}
Remove-Item Env:D39_PG_PW, Env:D39_MY_PW, Env:PG_VECTOR_PASSWORD -ErrorAction SilentlyContinue
