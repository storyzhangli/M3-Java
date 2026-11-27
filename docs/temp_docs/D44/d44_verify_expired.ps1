# d44_verify_expired.ps1 — A6 过期 token 独立探针（任务书 H7 补项；格数不变，335+probe 独立口径）
# 判据：expiredUser（d44_auth_principal.expires_at=2020-01-01，D44AuthSeed 已播）全端点 4030；
#       同端点 userA 有效 token=非4030（排除服务性故障假绿）；拒绝文案含"过期"语义。
# 纪律：仅本地 8080；不回显 token 明文；结果落 evidence\D44\matrix\expired-probe.json
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$ev = Join-Path $repo 'docs\evidence\D44\matrix'
$prin = Get-Content (Join-Path $repo 'docs\temp_docs\D44\auth\principals.local.json') -Raw | ConvertFrom-Json
$TOKEXP = $prin.principals.expiredUser.token
$TOKADM = $prin.principals.adminT1.token   # 对照=ADMIN（sync 域为 ADMIN-only，userA 对照不适用；admin 全端点非4030=服务活性证明）

function Probe($method, $path, $body, $tokStr) {
    $h = if ($tokStr) { @{ Authorization = "Bearer $tokStr" } } else { @{} }
    try {
        $p = @{ Uri = "$base$path"; Method = $method; Headers = $h; TimeoutSec = 40 }
        if ($body) { $p.ContentType = 'application/json; charset=utf-8'; $p.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress -Depth 6)) }
        $r = Invoke-RestMethod @p
        return @{ code = [string]$r.code; msg = [string]$r.msg }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) { $code = [int]$resp.StatusCode; return @{ code = "$code(HTTP)"; msg = '' } }
        return @{ code = 'ERR'; msg = $_.Exception.Message }
    }
}

$cases = @(
    @{ id = 'X1-read-docs';     m = 'Get';    p = '/api/v1/documents';                 b = $null },
    @{ id = 'X2-write-reg';     m = 'Post';   p = '/api/v1/documents/register';        b = @{ documentId = 'D44E2E-XPROBE-1'; source = 'D44-MX'; title = 'x'; tenantId = 'T1'; deptId = 'D1'; deviceId = 'DEV-B' } },
    @{ id = 'X3-search';        m = 'Post';   p = '/api/v1/retrieval/search';          b = @{ query = '主轴温度'; topK = 3 } },
    @{ id = 'X4-rag-ask';       m = 'Post';   p = '/api/v1/rag/ask';                   b = @{ question = '主轴过热先检查什么'; topK = 3 } },
    @{ id = 'X5-one-model';     m = 'Get';    p = '/api/v1/one/embeddings/model';      b = $null },
    @{ id = 'X6-prompt-list';   m = 'Get';    p = '/api/v1/ai/prompts';                b = $null },
    @{ id = 'X7-sync-status';   m = 'Get';    p = '/api/v1/sync/status';               b = $null }
)

$results = New-Object System.Collections.ArrayList
$fail = 0
foreach ($c in $cases) {
    $e = Probe $c.m $c.p $c.b $TOKEXP
    # 对照体：X2 用空 body（4001 校验拒绝即可证明服务活着，避免 userA 真建资产）
    $aBody = if ($c.id -eq 'X2-write-reg') { $null } else { $c.b }
    $a = Probe $c.m $c.p $aBody $TOKADM
    # 对照面有效性判据：userA 不得为 4030（服务活着，拒绝原因只能是凭据过期）
    $aValid = ($a.code -ne '4030')
    # 过期面判据：必须 4030
    $eDeny = ($e.code -eq '4030')
    # 文案语义：msg 含"过期"或"无效"（fail-closed 统一口径，不泄露差异原因）
    $msgOk = ($e.msg -match '过期|无效|认证')
    $verdict = if ($eDeny -and $aValid -and $msgOk) { 'PASS' } else { 'FAIL' }
    if ($verdict -eq 'FAIL') { $fail++ }
    [void]$results.Add([pscustomobject]@{ id = $c.id; expired = $e.code; adminless = $a.code; expMsg = $e.msg; verdict = $verdict })
    "{0,-16} expired={1,-6} userA={2,-6} {3} :: {4}" -f $c.id, $e.code, $a.code, $verdict, $e.msg
}
# 审计增量核验：过期请求也应被记录（fail-closed 留痕，D40 门禁审计面）
$repoM = 'D:\SoftwareInstall\Apache\repository'
$myjar = (Get-ChildItem "$repoM\com\mysql\mysql-connector-j\8.0.33" -Filter *.jar | Where-Object Name -notmatch 'sources' | Select-Object -First 1).FullName
$env:MYSQL_PASSWORD = '123456'
$audit = (& 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' '-Dfile.encoding=UTF-8' -cp $myjar (Join-Path $repo 'docs\temp_docs\D44\tools\D44QIA.java') 'jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true' root '123456') 2>&1 | Select-Object -Last 1
"AUDIT :: $audit"
$summary = [pscustomobject]@{
    scope      = 'A6 expired token 独立探针（第5 principal，335 格矩阵外补充，任务书 H7）'
    principal  = 'expiredUser(expires_at=2020-01-01)'
    cases      = $results
    fail       = $fail
    verdict    = $(if ($fail -eq 0) { 'ALL-PASS' } else { 'HAS-FAIL' })
    auditAfter = [string]$audit
    note       = '对照判据=同端点 ADMIN token 非4030（排除服务故障假绿；sync 域 ADMIN-only 故对照统一用 adminT1）；X2 若被 4030 拦截则无资产落库，无需清理'
}
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $ev 'expired-probe.json') -Encoding UTF8
"SUMMARY-> $ev\expired-probe.json  verdict=$($summary.verdict)"
