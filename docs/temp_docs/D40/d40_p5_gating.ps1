#requires -Version 7.0
# D40 P5-C14/C15 门禁与审计矩阵（实例 C：ACCESS_ENABLED=true，fixture token 经 DDL 种子 harness 落 d40_auth_principal，
# 明文仅 docs\temp_docs\D40\auth\fixture-tokens.json——禁入 Git/日志/证据，本脚本只引用不落明文）
# 探针选择：全部 ADMIN 通过位用零副作用调用（retry 无 FAILED 行=no-op、status/dryRun 只读、register 重复=4001 不建新行）
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8080/api/v1'
$ev   = 'E:\AI\project\AI-Learning-one\M3-Java\docs\evidence\D40'
$tp   = 'E:\AI\project\AI-Learning-one\M3-Java\docs\temp_docs\D40'
$jdk  = 'D:\SoftwareInstall\JDK\JDK21\bin'
$repo = 'D:\SoftwareInstall\Apache\repository'
$cp   = "$tp\ddl\classes;$tp\probe\classes;$repo\org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar;$repo\com\mysql\mysql-connector-j\8.0.33\mysql-connector-j-8.0.33.jar;$repo\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar;$repo\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar;$repo\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar"
$env:MYSQL_PASSWORD = '123456'
$env:PG_VECTOR_PASSWORD = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')
New-Item -ItemType Directory -Force "$ev\security" | Out-Null

$tok = Get-Content "$tp\auth\fixture-tokens.json" -Raw | ConvertFrom-Json
$H = @{ admin = @{ Authorization = "Bearer $($tok.principals.adminA1.token)" }; user = @{ Authorization = "Bearer $($tok.principals.userA1.token)" } }
function Probe($name, $method, $path, $headers, $body) {
    $p = @{ Uri = "$base$path"; Method = $method; ContentType = 'application/json; charset=utf-8' }
    if ($headers) { $p['Headers'] = $headers }
    if ($body) { $p['Body'] = $body }
    try {
        $r = Invoke-RestMethod @p
        $code = $r.code
    } catch { $code = "HTTP_EX:$($_.Exception.Message)" }
    [ordered]@{ probe = $name; method = $method; path = $path; code = $code } |
        ConvertTo-Json -Compress | Out-File -Append -Encoding utf8 "$ev\security\gating.jsonl"
    Write-Host "[PROBE] $name -> $code"
    $code
}
'' | Out-File -Encoding utf8 "$ev\security\gating.jsonl"   # 重置

# G1 匿名（期望 4030）
Probe 'G1a anon POST /sync/retry'          Post   '/sync/retry'                  $null '{"documentId":"__none__"}' | Out-Null
Probe 'G1b anon GET /sync/status'          Get    '/sync/status?documentId=d40-doc-a' $null $null | Out-Null
Probe 'G1c anon POST /vector-index/index'  Post   '/vector-index/index'          $null '{"documentId":"d40-doc-x","version":1,"strategy":"FIXED_SIZE","chunks":[{"chunkIndex":0,"content":"anon probe","globalStart":0,"globalEnd":9}]}' | Out-Null
Probe 'G1d anon DELETE /vector-index/documents/d40-doc-d' Delete '/vector-index/documents/d40-doc-d' $null $null | Out-Null
Probe 'G1e anon POST /documents/register'  Post   '/documents/register'          $null '{"documentId":"d40-doc-x","source":"probe","title":"anon"}' | Out-Null
# G2 非 ADMIN userA1（期望 4030）
Probe 'G2a user POST /sync/retry'          Post   '/sync/retry'                  $H.user '{"documentId":"__none__"}' | Out-Null
Probe 'G2b user DELETE /vector-index/documents/d40-doc-d' Delete '/vector-index/documents/d40-doc-d' $H.user $null | Out-Null
Probe 'G2c user POST /documents/register'  Post   '/documents/register'          $H.user '{"documentId":"d40-doc-y","source":"probe","title":"user"}' | Out-Null
# G3 ADMIN adminA1（期望业务码=放行）
Probe 'G3a admin GET /sync/status'         Get    '/sync/status?documentId=d40-doc-a' $H.admin $null | Out-Null
Probe 'G3b admin POST /sync/retry(noop)'   Post   '/sync/retry'                  $H.admin '{"documentId":"__no_such_doc__"}' | Out-Null
Probe 'G3c admin POST /sync/consistency(dryRun)' Post '/sync/consistency'        $H.admin '{"dryRun":true,"documentPrefix":"d40-doc-"}' | Out-Null
Probe 'G3d admin POST /documents/register(dup-4001)' Post '/documents/register'  $H.admin '{"documentId":"d40-doc-e1","source":"d40-fixture","title":"admin probe dup"}' | Out-Null
# G4 读面不受写门禁影响（documents GET 属 D39 既有可读性语义，非 ADMIN 也可读——如实记录）
Probe 'G4a user GET /documents/d40-doc-d'  Get    '/documents/d40-doc-d'         $H.user $null | Out-Null

# C15 审计留痕实查：d40_admin_audit 全行（token 明文不在表中，DB 仅 SHA-256；输出亦不含哈希值以外敏感项）
& "$jdk\java.exe" -cp $cp D40AuditDump "$ev\security\audit-trail.json" | Out-Host
Write-Host '[DONE] GATING_LOGGED'
