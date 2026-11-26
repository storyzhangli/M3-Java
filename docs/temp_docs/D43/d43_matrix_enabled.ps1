# d43_matrix_enabled.ps1 — D43-P1 越权矩阵（enabled 态；55 URL × 5 身份；协议=e2e-protocol securityMatrix）
# 用法：& d43_matrix_enabled.ps1 -Round pre   （缺陷态留证轮）
#       & d43_matrix_enabled.ps1 -Round post  （FIX-002/003 后复跑轮；可加 -ArmO 跑 one_ 族活体）
# 判定：expected 集命中=EXPECTED；G2/G4 USER 面 pre=200=PRE-DEFECT(设计内留证)；其余未命中=MISMATCH(新缺口→熔断#5)。
param([Parameter(Mandatory = $true)][string]$Round, [switch]$ArmO, [switch]$OneLive)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$evDir = Join-Path $repo 'docs\evidence\D43\matrix'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$P = Get-Content (Join-Path $evDir 'mx-params.json') -Raw | ConvertFrom-Json
$prin = Get-Content (Join-Path $repo 'docs\temp_docs\D43\auth\principals.local.json') -Raw | ConvertFrom-Json
$TOK = @{ admin = $prin.principals.adminT1.token; userA = $prin.principals.userA.token
          userB = $prin.principals.userB.token; userC = $prin.principals.userC.token }
$DOCA = $P.docA; $DOCB = $P.docB; $TID = $P.tableId
$SIDA = if ($P.ownSession) { $P.ownSession } else { 'd43e2e-mx-own-a1' }
$SIDB = "$($P.sessB)"
$ST = "$($P.stamp)$Round"
$MXA = 'MXKELVIN42'

function Call($method, $path, $body, $tokStr, $timeoutSec = 30) {
    $h = if ($tokStr) { @{ Authorization = "Bearer $tokStr" } } else { @{} }
    try {
        if ($null -ne $body) {
            $r = Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -ContentType 'application/json; charset=utf-8' -Body ($utf8.GetBytes(($body | ConvertTo-Json -Compress -Depth 6))) -TimeoutSec $timeoutSec
            if ($r -is [string]) { return [pscustomobject]@{ code = 200; msg = 'SSE-TEXT'; http = 200; raw = $r } }
            return [pscustomobject]@{ code = $r.code; msg = $r.msg; http = 200; raw = $r }
        }
        $r = Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -TimeoutSec $timeoutSec
        if ($r -is [string]) { return [pscustomobject]@{ code = 200; msg = 'SSE-TEXT'; http = 200; raw = $r } }
        return [pscustomobject]@{ code = $r.code; msg = $r.msg; http = 200; raw = $r }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            try { $sr = [IO.StreamReader]::new($resp.GetResponseStream()); $txt = $sr.ReadToEnd(); $sr.Close() } catch { $txt = '' }
            try { $j = $txt | ConvertFrom-Json; $c = $j.code } catch { $c = [int]$resp.StatusCode }
            return [pscustomobject]@{ code = $c; msg = "HTTP:$([int]$resp.StatusCode)"; http = [int]$resp.StatusCode; raw = $txt }
        }
        return [pscustomobject]@{ code = -1; msg = "TIMEOUT/CONN:$($_.Exception.Message.Substring(0,[Math]::Min(60,$_.Exception.Message.Length)))"; http = 0; raw = $null }
    }
}
function ImportForm($path, $docId, $tokStr) {
    $h = if ($tokStr) { @{ Authorization = "Bearer $tokStr" } } else { @{} }
    try {
        $r = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers $h -Form @{ file = Get-Item $path; documentId = $docId; source = 'D43-MX'; title = "MX $docId" } -TimeoutSec 90
        return [pscustomobject]@{ code = $r.code; msg = $r.msg; http = 200; raw = $r }
    } catch {
        $resp = $_.Exception.Response
        if ($resp) { return [pscustomobject]@{ code = [int]$resp.StatusCode; msg = 'HTTPERR'; http = [int]$resp.StatusCode; raw = $null } }
        return [pscustomobject]@{ code = -1; msg = 'TIMEOUT'; http = 0; raw = $null }
    }
}

# ---------- 端点宇宙（55，源码枚举=权威；cls=KP1 分级） ----------
# cls: ADM ADMIN面 | DOC DOC-SCOPE(目标 MXA) | OWN-S session列表 | OWN-M 他人会话 | OWN-C chat | SRV 检索族 | AUTH 只读元数据 | META G1残留 | PT G2写面 | ONE one_族 | WSTAR compat
$rows = New-Object System.Collections.ArrayList
function AddRow($id, $m, $p, $cls, $body, $leak = $null, $expectOverride = $null) {
    [void]$rows.Add([pscustomobject]@{ id = $id; method = $m; path = $p; cls = $cls; body = $body; leak = $leak; ov = $expectOverride })
}
AddRow 'E00-doc-reg'     Post '/api/v1/documents/register'      ADM @{ documentId = "D43E2E-MX-REG-$ST"; source = 'D43-MX'; title = "MX REG $ST"; tenantId = 'T1'; deptId = 'D1'; deviceId = 'DEV-A' }
AddRow 'E01-chat'        Post '/api/v1/ai/chat'                OWN-C @{ message = "mx $ST" }
AddRow 'E02-health'      Get  '/api/v1/ai/health'              AUTH $null
AddRow 'E03-stream'      Post '/api/v1/ai/chat/stream'         OWN-C @{ message = "mx $ST" }
AddRow 'E04-device'      Post '/api/v1/device/chat'            OWN-C @{ scene = 'device_customer_service'; message = "mx $ST" }
AddRow 'E05-sessions'    Get  '/api/v1/ai/sessions'            OWN-S $null
AddRow 'E06-messages-ownA' Get "/api/v1/ai/sessions/$SIDA/messages" OWN-M $null
AddRow 'E07-doc-get'     Get  "/api/v1/documents/$DOCA"        META $null
AddRow 'E08-doc-list'    Get  '/api/v1/documents'              META $null
AddRow 'E09-pages'       Get  "/api/v1/documents/$DOCA/pages"  DOC $null
AddRow 'E10-page1'       Get  "/api/v1/documents/$DOCA/pages/1" DOC $null
AddRow 'E11-chunks'      Get  "/api/v1/documents/$DOCA/chunks" DOC $null
AddRow 'E12-chunkscmp'   Get  "/api/v1/documents/$DOCA/chunks/comparison" DOC $null
AddRow 'E13-embed-doc'   Post "/api/v1/documents/$DOCA/embeddings?strategy=FIXED_SIZE" DOC $null
AddRow 'E14-embed-model' Get  '/api/v1/embeddings/model'       AUTH $null
AddRow 'E15-embed-compat' Post '/api/v1/embeddings/index/compatibility' WSTAR @{ model = 'mock-local'; dimension = 384 }
AddRow 'E16-search'      Post '/api/v1/retrieval/search'       SRV @{ query = "主轴温度 $MXA"; topK = 5 } 'MXA'
AddRow 'E17-sync-status' Get  '/api/v1/sync/status'            ADM $null
AddRow 'E18-sync-retry'  Post '/api/v1/sync/retry'             ADM @{ documentIds = @($DOCA) }
AddRow 'E19-sync-cons'   Post '/api/v1/sync/consistency'       ADM @{ documentIds = @($DOCA) }
AddRow 'E20-tables'      Get  "/api/v1/documents/$DOCA/tables" DOC $null
AddRow 'E21-rows'        Get  "/api/v1/tables/$TID/rows"       DOC $null
AddRow 'E22-tbl-query'   Get  "/api/v1/documents/$DOCA/tables/query?column=项目编号&value=MX-01&limit=5" DOC $null
AddRow 'E23-tbl-extract' Post "/api/v1/documents/$DOCA/tables/extract" ADM $null
AddRow 'E24-vi-index'    Post '/api/v1/vector-index/index'     ADM @{ documentId = $DOCA; version = 1; strategy = 'STRUCTURE'; title = 'MX VI direct'; chunks = @(@{ chunkIndex = 0; content = "mx vi direct $ST"; globalStart = 0; globalEnd = 10 }) }
AddRow 'E25-vi-idx-pages' Post "/api/v1/vector-index/documents/$DOCA/index" ADM $null
AddRow 'E26-vi-reconcile' Post '/api/v1/vector-index/reconcile' ADM @{ dryRun = $true }
AddRow 'E27-vi-recover'  Post '/api/v1/vector-index/recover'   ADM $null
AddRow 'E28-vi-status'   Get  "/api/v1/vector-index/documents/$DOCA/status" DOC $null
AddRow 'E29-vi-chunks'   Get  "/api/v1/vector-index/documents/$DOCA/chunks" DOC $null
AddRow 'E30-vs-docs'     Post '/api/v1/vector-store/documents' ADM @{ items = @(@{ text = "mx raw $ST"; metadata = @{ documentId = "D43E2E-MX-RAW-$Round" } }) }
AddRow 'E31-vs-search'   Post '/api/v1/vector-store/search'    ADM @{ query = 'mx raw'; topK = 2; threshold = 0.0 }
AddRow 'E32-vs-stats'    Get  '/api/v1/vector-store/stats'     ADM $null
AddRow 'E33-hk-rebuild'  Post '/api/v1/hybrid/keyword-index/rebuild' ADM $null $null @(4030, 5000, 200)
AddRow 'E34-hk-stats'    Get  '/api/v1/hybrid/keyword-index/stats' AUTH $null
AddRow 'E35-hybrid-ask'  Post '/api/v1/hybrid/rag/ask'         SRV @{ question = "主轴过热 $MXA"; topK = 5 } 'MXA'
AddRow 'E36-hybrid-srch' Post '/api/v1/hybrid/retrieval/search' SRV @{ query = "主轴温度 $MXA"; topK = 5 } 'MXA'
AddRow 'E37-rag-ask'     Post '/api/v1/rag/ask'                SRV @{ question = "主轴过热先检查什么 $MXA"; topK = 5 } 'MXA'
AddRow 'E38-prompt-list' Get  '/api/v1/ai/prompts'             AUTH $null
AddRow 'E39-prompt-get'  Get  '/api/v1/ai/prompts/d43e2e-mx-pt-a' AUTH $null
AddRow 'E40-prompt-roles' Get '/api/v1/ai/roles'               AUTH $null
AddRow 'E41-one-embed-use' Get '/api/v1/one/embeddings/usage'  ONE $null
AddRow 'E42-one-embed-model' Get '/api/v1/one/embeddings/model' ONE $null
AddRow 'E43-one-embed-compat' Post '/api/v1/one/embeddings/index/compatibility' ONE @{ model = 'bailian'; dimension = 1024 }
AddRow 'E44-one-rag-ask' Post '/api/v1/one/rag/ask'            ONE @{ question = "mx $ST" }
AddRow 'E45-one-search'  Post '/api/v1/one/retrieval/search'   ONE @{ query = 'mx'; topK = 2 }
AddRow 'E46-one-vi-index' Post '/api/v1/one/vector-index/index' ONE @{ documentId = "D43E2E-MX-ONE-$Round"; version = 1; strategy = 'STRUCTURE'; title = 'mx one'; chunks = @(@{ chunkIndex = 0; content = "mx one chunk $ST"; globalStart = 0; globalEnd = 10 }) }
AddRow 'E47-one-vi-del'  Delete '/api/v1/one/vector-index/documents/D43E2E-MX-ONE' ONE $null
AddRow 'E48-one-vi-status' Get '/api/v1/one/vector-index/documents/D43E2E-MXA-001/status' ONE $null
AddRow 'E49-one-vi-chunks' Get '/api/v1/one/vector-index/documents/D43E2E-MXA-001/chunks' ONE $null
AddRow 'E50-one-vi-reconcile' Post '/api/v1/one/vector-index/reconcile?dryRun=true' ONE $null
AddRow 'E51-pt-post'     Post '/api/v1/ai/prompts'             PT @{ templateId = "d43e2e-mx-ptc-$Round-x"; name = "MX C $Round"; scene = 'customer_service'; content = "mx $ST" }
AddRow 'E52-pt-put'      Put  '/api/v1/ai/prompts/d43e2e-mx-pt-a' PT @{ templateId = 'd43e2e-mx-pt-a'; name = "MX A upd $ST"; scene = 'customer_service'; content = "mx upd $ST" }
AddRow 'E53-pt-patch'    Patch '/api/v1/ai/prompts/d43e2e-mx-pt-a/status' PT @{ status = 'ENABLED' }
AddRow 'E54-pt-delete'   Delete "/api/v1/ai/prompts/d43e2e-mx-PLACEHOLDER" PT $null
AddRow 'E55-doc-imp'     Import '/api/v1/documents/import'     ADM $null
AddRow 'E56-vi-del'      Delete "/api/v1/vector-index/documents/D43E2E-MX-REG-$ST" ADM $null

# ---------- 预期规则 ----------
function Expect($cls, $role, $rowId, $ov) {
    if ($role -eq 'ANON') { return @{ set = @(4030); kind = 'DENY' } }
    if ($ov) { return @{ set = @($ov); kind = 'OBS-BEAN-OFF' } }
    if ($cls -eq 'ONE' -and -not $ArmO -and -not $OneLive) {
        if ($role -eq 'ANON') { return @{ set = @(4030); kind = 'DENY' } }
        return @{ set = @(404, 5000, -1, 4031); kind = 'BEAN_ABSENT' }
    }
    switch ($cls) {
        'ADM'  {
            if ($role -eq 'admin' -and $rowId -eq 'E24-vi-index') { return @{ set = @(200, 4001, 5000); kind = 'OK-GUARD' } }
            if ($role -eq 'admin') { return @{ set = @(200); kind = 'OK' } } else { return @{ set = @(4030); kind = 'DENY' } }
        }
        # KP1 冻结分级：one_ 写面 3 行（E46 index/E47 del/E50 reconcile）=G4 双口径；
        # ask/search=SERVICE-PRE；usage/model/status/chunks/compat=读面（无门禁，业务码域）
        'ONE'  {
            if ($rowId -in 'E46-one-vi-index', 'E47-one-vi-del', 'E50-one-vi-reconcile') {
                if ($role -eq 'admin') { return @{ set = @(200, 4001, 5000); kind = 'OK' } }
                if ($Round -eq 'pre') { return @{ set = @(200, 4001, 5000, -1); kind = 'PRE-DEFECT?' } }
                return @{ set = @(4030); kind = 'DENY-POST' }
            }
            return @{ set = @(200, 4001, 5000, -1); kind = 'ONE-RW' }
        }
        'DOC'  {
            $s = switch ($role) { 'admin' { @(200) } 'userA' { @(200) } 'userB' { @(4030) } 'userC' { @(4030) } }
            $k = if ($role -in 'userB', 'userC') { 'DENY' } else { 'OK' }
            if ($role -eq 'userA' -and $rowId -eq 'E13-embed-doc') { $s = @(200, 4001, 5000) }
            return @{ set = $s; kind = $k }
        }
        'OWN-S' { return @{ set = @(200); kind = 'OK' } }
        'OWN-M' {
            $s = switch ($role) { 'userA' { @(200) } 'userB' { @(4030, 4001) } 'userC' { @(4030, 4001) } 'admin' { @(4030, 4001) } }
            $k = if ($role -in 'userB', 'userC', 'admin') { 'REJECT' } else { 'OK' }
            return @{ set = $s; kind = $k }
        }
        'OWN-C' { return @{ set = @(200, 4001, 5000); kind = 'OK' } }
        'SRV'   { return @{ set = @(200, 5000); kind = 'SRV' } }
        'AUTH'  { return @{ set = @(200, 5000); kind = 'OK' } }
        'META'  { return @{ set = @(200); kind = 'RESIDUAL-META' } }
        'WSTAR' { return @{ set = @(200, 4001, 5000); kind = 'OK' } }
        'PT'    {
            if ($role -eq 'admin') { return @{ set = @(200); kind = 'OK' } }
            if ($Round -eq 'pre') { return @{ set = @(200); kind = 'PRE-DEFECT' } }
            return @{ set = @(4030); kind = 'DENY-POST' }
        }
    }
}

# ---------- 审计基线 ----------
# tableId 跨轮漂移（E23 重抽取生成新表 + reconcile 清悬空）→ 跑前取活值覆盖冻结参数
try { $tvL = (Call Get "/api/v1/documents/$DOCA/tables" $null $TOK.admin).raw; if (@($tvL.data).Count -ge 1) { $TID = $tvL.data[0].id; "TID-LIVE=$TID (param=$($P.tableId))" } else { "TID-LIVE=none keep $TID" } } catch { "TID-LIVE query failed keep $TID" }
$jdk = 'D:\SoftwareInstall\JDK\JDK21\bin'; $repoM = 'D:\SoftwareInstall\Apache\repository'
$myjar = (Get-ChildItem "$repoM\com\mysql\mysql-connector-j\8.0.33" -Filter *.jar | Where-Object Name -notmatch 'sources' | Select-Object -First 1).FullName
$mpw = [Environment]::GetEnvironmentVariable('MYSQL_PASSWORD', 'User'); if (-not $mpw) { $mpw = '123456' }
$sql = 'jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true'
$audit0 = (& "$jdk\java.exe" '-Dfile.encoding=UTF-8' -cp "docs\temp_docs\D43\tools;$myjar" D43QIA $sql root $mpw) 2>&1 | Select-Object -Last 1
"AUDIT-BEFORE :: $audit0"

# ---------- 执行 ----------
$results = New-Object System.Collections.ArrayList
$roles = 'ANON', 'admin', 'userA', 'userB', 'userC'
foreach ($row in $rows) {
    if ($ArmO -and $row.cls -ne 'ONE') { continue }
    if (-not $ArmO -and $row.id -eq 'SKIP-NONE') { continue }
    foreach ($role in $roles) {
        $tokStr = if ($role -eq 'ANON') { $null } else { $TOK[$role] }
        $body = $row.body
        $path = $row.path
        # G2 POST/DELETE 每身份独立资产（防互相污染/顺序假 MISMATCH）
        if ($row.id -eq 'E51-pt-post') { $body = @{ templateId = "d43e2e-mx-ptc-$ST-$role"; name = "MX $role $ST"; scene = 'customer_service'; content = "mx $ST $role" } }
        if ($row.id -eq 'E54-pt-delete') { $path = "/api/v1/ai/prompts/d43e2e-mx-ptc-$ST-$role" }
        $r = if ($row.id -eq 'E55-doc-imp') {
            $fx = Join-Path $repo "docs\temp_docs\D43\e2e\e2e-mx-imp-$Round-$role.md"
            if (-not (Test-Path $fx)) { [IO.File]::WriteAllText($fx, "# mx imp $Round $role stamp $ST`nmarker MXIMP-$Round-$role`n", $utf8) }
            $did = "D43E2E-MX-IMP-$Round-$role"
            if ($role -eq 'admin') { [void](Call Post '/api/v1/documents/register' @{ documentId = $did; source = 'D43-MX'; title = "MX IMP $role"; tenantId = 'T1'; deptId = 'D1'; deviceId = 'DEV-A' } $tokStr) }
            ImportForm $fx $did $tokStr
        } elseif ($row.id -eq 'E03-stream') {
            Call $row.method $path $body $tokStr 12
        } elseif ($row.method -eq 'Delete' -or $row.method -eq 'Get') {
            Call $row.method $path $null $tokStr
        } else {
            Call $row.method $path $body $tokStr
        }
        $exp = Expect $row.cls $role $row.id $row.ov
        $hit = ($exp.set -contains [int]$r.code)
        # 泄露核验（SRV 面 userB/userC）
        $leakNote = ''
        if ($row.leak -eq 'MXA' -and $role -in 'userB', 'userC' -and $r.code -eq 200) {
            # 仅扫数据字段（query 回显含标记词=假阳性，源码 SimilaritySearchResultView.query 实证）
            $d = if ($r.raw -is [string]) { $null } else { $r.raw.data }
            $scan = ''
            if ($d.matches) { $scan += ((@($d.matches) | ForEach-Object { "$($_.documentId)|$($_.text)" }) -join '~') }
            if ($d.citations) { $scan += ((@($d.citations) | ForEach-Object { "$($_.documentId)|$($_.quote)|$($_.text)" }) -join '~') }
            if ($d.answer) { $scan += '|' + [string]$d.answer }
            if ($null -eq $d) { $scan = $r.raw }
            $hasA = $scan -match [regex]::Escape($DOCA) -or $scan -match $MXA
            $leakNote = if ($hasA) { 'LEAK!!' } else { 'no-leak-ok' }
            if ($hasA) { $hit = $false }
        }
        $verdict = if ($hit) { $exp.kind } else { 'MISMATCH' }
        [void]$results.Add([pscustomobject]@{ id = $row.id; role = $role; cls = $row.cls; code = "$($r.code)"; expect = ($exp.set -join '/'); verdict = $verdict; leak = $leakNote; msg = ("$($r.msg)".Substring(0, [Math]::Min(60, "$($r.msg)".Length))) })
        $mark = if ($verdict -eq 'MISMATCH') { '>>MISMATCH' } else { '' }
        ("{0,-22} {1,-6} code={2,-5} exp={3,-14} {4}{5} {6}" -f $row.id, $role, $r.code, ($exp.set -join '/'), $verdict, $mark, $leakNote)
    }
}
$audit1 = (& "$jdk\java.exe" '-Dfile.encoding=UTF-8' -cp "docs\temp_docs\D43\tools;$myjar" D43QIA $sql root $mpw) 2>&1 | Select-Object -Last 1
"AUDIT-AFTER :: $audit1"
$mm = @($results | Where-Object verdict -eq 'MISMATCH')
$pd = @($results | Where-Object verdict -eq 'PRE-DEFECT')
$LK = @($results | Where-Object leak -eq 'LEAK!!')
"==== ROUND=$Round armO=$([bool]$ArmO) cells=$($results.Count) MISMATCH=$($mm.Count) PRE-DEFECT=$($pd.Count) LEAK=$($LK.Count) ===="
if ($mm.Count) { $mm | ForEach-Object { "MM :: $($_.id)/$($_.role) code=$($_.code) exp=$($_.expect) msg=$($_.msg)" } }
$outf = Join-Path $evDir ('matrix-results-{0}{1}.json' -f $Round, $(if ($ArmO) { '-armo' } else { '' }))
[IO.File]::WriteAllText($outf, ($results | ConvertTo-Json -Depth 4), $utf8)
"RESULTS-> $outf"
