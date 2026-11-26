# d43_matrix_refix.ps1 — enabled 态矩阵夹具最小修复（不重跑 disabled 扫，零覆盖 matrix-disabled-12.json）
# 背景：外部 18080 实例清库致 MXA/MXB 文档行缺失（Advice 折叠=DOC 面全 4030 假象）。本脚本 admin Bearer 重建。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$evDir = Join-Path $repo 'docs\evidence\D43\matrix'
$P = Get-Content (Join-Path $evDir 'mx-params.json') -Raw | ConvertFrom-Json
$prin = Get-Content (Join-Path $repo 'docs\temp_docs\D43\auth\principals.local.json') -Raw | ConvertFrom-Json
$tok = $prin.principals.adminT1.token
$H = @{ Authorization = "Bearer $tok" }
$HA = @{ Authorization = "Bearer $($prin.principals.userA.token)" }

function Api($method, $path, $body, $h) {
    if (-not $h) { $h = $H }
    if ($null -ne $body) {
        return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes(($body | ConvertTo-Json -Compress -Depth 6))) -TimeoutSec 60
    }
    return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -TimeoutSec 60
}

# ---------- 1) MXA / MXB 夹具（新 stamp） ----------
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
$fxA = Join-Path $repo 'docs\temp_docs\D43\e2e\e2e-mx-owner-a.md'
[IO.File]::WriteAllLines($fxA, [string[]]($mdA -split "`n"), $utf8)
$mdB = $mdA -replace 'MXKELVIN42', 'MXKELVIN43' -replace "stamp $stamp", "stamp B$stamp" -replace 'stamp-' + $stamp, ('stamp-B' + $stamp)
$fxB = Join-Path $repo 'docs\temp_docs\D43\e2e\e2e-mx-other-b.md'
[IO.File]::WriteAllLines($fxB, [string[]]($mdB -split "`n"), $utf8)

foreach ($d in @(
    @{ id = 'D43E2E-MXA-001'; t = 'T1'; dp = 'D1'; dv = 'DEV-A'; f = $fxA; ttl = 'MX ownerA doc' },
    @{ id = 'D43E2E-MXB-002'; t = 'T2'; dp = 'D2'; dv = 'DEV-B'; f = $fxB; ttl = 'MX userB doc' })) {
    $r = Api Post '/api/v1/documents/register' @{ documentId = $d.id; source = 'D43-MX'; title = $d.ttl; tenantId = $d.t; deptId = $d.dp; deviceId = $d.dv } $H
    "register $($d.id) code=$($r.code)"
    $ri = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers $H -Form @{ file = Get-Item $d.f; documentId = $d.id; source = 'D43-MX'; title = $d.ttl } -TimeoutSec 120
    "import $($d.id) code=$($ri.code) status=$($ri.data.status)"
}
$r3 = Api Post '/api/v1/vector-index/documents/D43E2E-MXA-001/index' $null $H
"index MXA code=$($r3.code) status=$($r3.data.status) chunks=$($r3.data.chunkCount)"

# ---------- 2) userA 会话（enabled：服务端生成） ----------
$ch = Api Post '/api/v1/ai/chat' @{ message = "MX refix owner session $stamp" } $HA
$sid = $ch.data.sessionId
"chat userA code=$($ch.code) sid=$sid"

# ---------- 3) pt-a 模板（若被清则补建；重复容忍 4001） ----------
$rpt = Api Post '/api/v1/ai/prompts' @{ templateId = 'd43e2e-mx-pt-a'; name = 'MX PT A'; scene = 'SERVICE'; content = "mx owner prompt $stamp" } $H
"create pt-a code=$($rpt.code)"

# ---------- 4) 核验 + 参数更新 ----------
$tv = Api Get '/api/v1/documents/D43E2E-MXA-001/tables' $null $H
$tid = if (@($tv.data).Count -ge 1) { $tv.data[0].id } else { $P.tableId }
"tableId=$tid tables=$(@($tv.data).Count)"
$pg = Api Post '/api/v1/retrieval/search' @{ query = "主轴温度 MXKELVIN42"; topK = 5 } $H
"admin-search hits=$(@($pg.data.matches).Count) docs=$( (@($pg.data.matches)|ForEach-Object {$_.documentId}) -join ',' )"
$rb = Api Post '/api/v1/retrieval/search' @{ query = "主轴温度 MXKELVIN42"; topK = 5 } @{ Authorization = "Bearer $($prin.principals.userB.token)" }
"userB-search hits=$(@($rb.data.matches).Count) leakScan=$( (@($rb.data.matches)|ForEach-Object {$_.documentId+$_.text}) -join ',' )"

$newP = [ordered]@{
    stamp = $stamp; tableId = $tid
    docA = 'D43E2E-MXA-001'; docB = 'D43E2E-MXB-002'
    ownSession = $sid; sessB = $P.sessB
    ptA = 'd43e2e-mx-pt-a'; ptB = 'd43e2e-mx-pt-b'; ptC = 'd43e2e-mx-pt-c'
    tokensSha12 = $P.tokensSha12
}
[IO.File]::WriteAllText((Join-Path $evDir 'mx-params.json'), ($newP | ConvertTo-Json -Depth 6), $utf8)
'REFIX-DONE stamp=' + $stamp
