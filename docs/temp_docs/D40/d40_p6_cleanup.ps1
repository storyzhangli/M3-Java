# d40_p6_cleanup.ps1 — C19 d40-% 全域夹具清理（管线 delete→DB 级清理→指纹恢复核验）
# 前置：默认实例(r5，access=false 无注入)。留痕 docs\evidence\D40\cleanup\
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080/api/v1'
$out = Join-Path $repo 'docs\evidence\D40\cleanup'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$jdk = 'D:\SoftwareInstall\JDK\JDK21\bin'
$mvn = 'D:\SoftwareInstall\Apache\repository'
$cp = "$mvn\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar;$mvn\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar;$mvn\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar;$mvn\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar;$mvn\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar;$repo\docs\temp_docs\D40\probe\tools"
$env:MYSQL_PASSWORD = '123456'
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')
$docs = 'a','b','c','d','e1','f','f2','g','h','j','k','up'

# 1) 管线删除收口（tombstone 走幂等 SYNCED 短路；FAILED/ACTIVE 走完整闭环）
foreach ($d in $docs) {
    $id = "d40-doc-$d"
    $r = Invoke-RestMethod "$base/vector-index/documents/$id" -Method Delete -TimeoutSec 60
    Start-Sleep -Milliseconds 400
    $st = Invoke-RestMethod "$base/vector-index/documents/$id/sync/status?operation=DELETE"
    $row = if (@($st.data.rows).Count -gt 0) { @($st.data.rows)[-1] } else { 'NO_ROW' }
    $sync = if ($row -is [string]) { 'FAILED' } else { $row.syncStatus }
    "{0} deleteCode={1} deleteResult={2} syncStatus={3}" -f $id, $r.code, $r.data.result, $sync
}

# 2) DB 级：dryRun 留痕 → apply → 复核
& "$jdk\java.exe" -cp $cp D40Cleanup (Join-Path $out 'cleanup-dryrun.json')
& "$jdk\java.exe" -cp $cp D40Cleanup (Join-Path $out 'cleanup-apply.json') apply
& "$jdk\java.exe" -cp $cp D40Cleanup (Join-Path $out 'cleanup-verify.json')

# 3) 指纹恢复核验（D40Probe 重跑 stdout 重定向，fingerprint 须=10fc69d5 语料 8 行态）
& "$jdk\java.exe" -cp $cp D40Probe *> (Join-Path $out 'postclean-inventory.json')
'CLEANUP_DONE'
