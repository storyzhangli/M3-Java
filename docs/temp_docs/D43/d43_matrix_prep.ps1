# d43_matrix_prep.ps1 — 阶段1：越权矩阵夹具创建 + disabled 态 12 代表端点扫（零门禁差异证明）
# 前置：8080 实例运行于 baseline 态（ACCESS 关闭）。夹具全部 D43E2E-* 命名空间（E2EClean 可清）。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$evDir = Join-Path $repo 'docs\evidence\D43\matrix'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$principals = Get-Content (Join-Path $repo 'docs\temp_docs\D43\auth\principals.local.json') -Raw | ConvertFrom-Json
$tok = @{ userA = $principals.principals.userA.token; userB = $principals.principals.userB.token
          userC = $principals.principals.userC.token; adminT1 = $principals.principals.adminT1.token }

function Api($method, $path, $body, $hdr) {
    $h = if ($hdr) { $hdr } else { @{ 'X-Dev-User' = 'dev-e2e' } }
    if ($null -ne $body) {
        $enc = [System.Text.Encoding]::UTF8
        try {
            return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -ContentType 'application/json; charset=utf-8' -Body ($enc.GetBytes(($body | ConvertTo-Json -Compress -Depth 6))) -TimeoutSec 60
        } catch { return [pscustomobject]@{ code = -1; http = 0; msg = $_.Exception.Message } }
    }
    try { return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -TimeoutSec 60 }
    catch { return [pscustomobject]@{ code = -1; http = 0; msg = $_.Exception.Message } }
}
function BearerApi($method, $path, $body, $tokStr) {
    $h = if ($tokStr) { @{ Authorization = "Bearer $tokStr" } } else { @{} }
    Api $method $path $body $h
}

# ---------- 夹具 md（含管道表格 + device 列 + 唯一标记） ----------
$stamp = (Get-Random -Maximum 999999).ToString('D6')
$mdA = @"
# D43 MX fixture A (stamp $stamp)

## 冷却系统巡检表

| 项目编号 | 检查部位 | 标准值 | 实测值 | 单位 | 设备 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| MX-01 | 冷却泵压力 | 0.35 | 0.41 | MPa | DEV-A |
| MX-02 | 主轴温度 | 65 | 78 | 摄氏度 | DEV-A |
| MX-03 | 油箱液位 | 80 | 82 | 百分比 | DEV-B |

主轴过热标记词 MXKELVIN42：先查冷却泵压力再查过滤器堵塞。stamp-$stamp
"@
$fxPath = Join-Path $repo 'docs\temp_docs\D43\e2e\e2e-mx-owner-a.md'
[IO.File]::WriteAllLines($fxPath, [string[]]($mdA -split "`n"), $utf8)
$mdB = $mdA -replace 'MXKELVIN42', 'MXKELVIN43' -replace "stamp $stamp", "stamp B$stamp" -replace 'stamp-' + $stamp, ('stamp-B' + $stamp)
$fxPathB = Join-Path $repo 'docs\temp_docs\D43\e2e\e2e-mx-other-b.md'
[IO.File]::WriteAllLines($fxPathB, [string[]]($mdB -split "`n"), $utf8)

'=== 1) 夹具：MXA（T1/D1/DEV-A owner=userA scope） ==='
$r1 = Api Post '/api/v1/documents/register' @{ documentId = 'D43E2E-MXA-001'; source = 'D43-MX'; title = 'MX ownerA doc'; tenantId = 'T1'; deptId = 'D1'; deviceId = 'DEV-A' } $null
"register MXA code=$($r1.code)"
$r2 = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers @{ 'X-Dev-User' = 'dev-e2e' } -Form @{ file = Get-Item $fxPath; documentId = 'D43E2E-MXA-001'; source = 'D43-MX'; title = 'MX ownerA doc' } -TimeoutSec 120
"import MXA code=$($r2.code) status=$($r2.data.status)"
$r3 = Api Post '/api/v1/vector-index/documents/D43E2E-MXA-001/index' $null $null
"index MXA code=$($r3.code) status=$($r3.data.status) chunks=$($r3.data.chunkCount)"

'=== 2) 夹具：MXB（T2/D2/DEV-B userB scope，供 userB-owner 正向面） ==='
$r4 = Api Post '/api/v1/documents/register' @{ documentId = 'D43E2E-MXB-002'; source = 'D43-MX'; title = 'MX userB doc'; tenantId = 'T2'; deptId = 'D2'; deviceId = 'DEV-B' } $null
"register MXB code=$($r4.code)"
$r5 = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers @{ 'X-Dev-User' = 'dev-e2e' } -Form @{ file = Get-Item $fxPathB; documentId = 'D43E2E-MXB-002'; source = 'D43-MX'; title = 'MX userB doc' } -TimeoutSec 120
"import MXB code=$($r5.code) status=$($r5.data.status)"

'=== 3) 夹具：tables 参数 ==='
$tv = Api Get '/api/v1/documents/D43E2E-MXA-001/tables' $null $null
$tid = if ($tv.data.Count -ge 1) { $tv.data[0].id } else { 'NO-TABLE' }
"tableId=$tid tables=$($tv.data.Count)"

'=== 4) 夹具：chat 会话（owner=userA，X-Dev-User 信任通道=disabled 态语义） ==='
$ownSess = 'd43e2e-mx-own-a1'
$ch = Api Post '/api/v1/ai/chat' @{ message = "MX owner session probe $stamp"; sessionId = $ownSess } @{ 'X-Dev-User' = 'userA' }
"chat userA code=$($ch.code) sid=$($ch.data.sessionId)"

'=== 5) 夹具：prompt 模板（A 资产 + B 资产 + 扫尾用 C） ==='
$ptA = Api Post '/api/v1/ai/prompts' @{ templateId = 'd43e2e-mx-pt-a'; name = 'MX PT A'; scene = 'SERVICE'; content = "mx owner prompt $stamp" } @{ 'X-Dev-User' = 'userA' }
"create pt-a code=$($ptA.code)"
$ptB = Api Post '/api/v1/ai/prompts' @{ templateId = 'd43e2e-mx-pt-b'; name = 'MX PT B'; scene = 'SERVICE'; content = "mx userB prompt $stamp" } @{ 'X-Dev-User' = 'userB' }
"create pt-b code=$($ptB.code)"
$ptC = Api Post '/api/v1/ai/prompts' @{ templateId = 'd43e2e-mx-pt-c'; name = 'MX PT C'; scene = 'SERVICE'; content = 'mx sweep only' } @{ 'X-Dev-User' = 'userC' }
"create pt-c code=$($ptC.code)"

# ---------- disabled 代表 12 端点 × {ANON, userA}：断言=非4030（零门禁） ----------
'=== 6) disabled 12 代表扫 ==='
$rep = @(
    @{ id = 'M-D01'; m = 'Get';    p = '/api/v1/ai/health';                                        b = $null },
    @{ id = 'M-D02'; m = 'Get';    p = '/api/v1/documents';                                        b = $null },
    @{ id = 'M-D03'; m = 'Get';    p = '/api/v1/documents/D43E2E-MXA-001/pages';                   b = $null },
    @{ id = 'M-D04'; m = 'Get';    p = '/api/v1/vector-index/documents/D43E2E-MXA-001/status';      b = $null },
    @{ id = 'M-D05'; m = 'Post';   p = '/api/v1/retrieval/search';                                 b = @{ query = '主轴温度超过上限'; topK = 5 } },
    @{ id = 'M-D06'; m = 'Post';   p = '/api/v1/rag/ask';                                          b = @{ question = '主轴过热先检查什么'; topK = 5 } },
    @{ id = 'M-D07'; m = 'Get';    p = '/api/v1/ai/prompts';                                       b = $null },
    @{ id = 'M-D08'; m = 'Get';    p = '/api/v1/sync/status';                                      b = $null },
    @{ id = 'M-D09'; m = 'Get';    p = '/api/v1/embeddings/model';                                 b = $null },
    @{ id = 'M-D10'; m = 'Get';    p = '/api/v1/hybrid/keyword-index/stats';                       b = $null },
    @{ id = 'M-D11'; m = 'Get';    p = '/api/v1/one/embeddings/model';                             b = $null },
    @{ id = 'M-D12'; m = 'Get';    p = "/api/v1/ai/sessions";                                      b = $null }
)
$out = New-Object System.Collections.ArrayList
foreach ($e in $rep) {
    foreach ($role in 'ANON', 'userA') {
        $r = BearerApi $e.m $e.p $e.b $(if ($role -eq 'ANON') { $null } else { $tok[$role] })
        $code = if ($null -ne $r.code) { $r.code } elseif ($r.PSObject.Properties['status']) { $r.status } else { 'ERR' }
        $ok = ($code -ne 4030)
        [void]$out.Add([pscustomobject]@{ id = $e.id; role = $role; code = "$code"; noGate = $ok })
        "$($e.id)/$role code=$code $(if($ok){'[OK-零门禁]'}else{'[FAIL-出现4030]'})"
    }
}
# G2 disabled 零差异写面证据：userC 建后即删（pt-c 一次性）
$rd = Api Delete '/api/v1/ai/prompts/d43e2e-mx-pt-c' $null @{ 'X-Dev-User' = 'userC' }
"M-D13/G2-del-userC code=$($rd.code) $(if($rd.code -ne 4030){'[OK-零门禁]'}else{'[FAIL]'})"
[void]$out.Add([pscustomobject]@{ id = 'M-D13-G2'; role = 'userC'; code = "$($rd.code)"; noGate = ($rd.code -ne 4030) })

# ---------- 参数冻结文件 ----------
$params = [pscustomobject]@{
    stamp = $stamp; tableId = "$tid"; ownSession = $ownSess
    docA = 'D43E2E-MXA-001'; docB = 'D43E2E-MXB-002'
    ptA = 'd43e2e-mx-pt-a'; ptB = 'd43e2e-mx-pt-b'
    markerA = 'MXKELVIN42'; markerB = 'MXKELVIN43'
    tokensSha12 = [pscustomobject]@{ userA = $principals.principals.userA.tokenSha256.Substring(0,12); userB = $principals.principals.userB.tokenSha256.Substring(0,12); userC = $principals.principals.userC.tokenSha256.Substring(0,12); adminT1 = $principals.principals.adminT1.tokenSha256.Substring(0,12) }
}
$pp = Join-Path $evDir 'mx-params.json'
[IO.File]::WriteAllText($pp, ($params | ConvertTo-Json -Depth 5), $utf8)
"PARAMS-> $pp"
$jp = Join-Path $evDir 'matrix-disabled-12.json'
[IO.File]::WriteAllText($jp, (($out | Sort-Object id, role) | ConvertTo-Json -Depth 4), $utf8)
"DISABLED-SWEEP-> $jp"
