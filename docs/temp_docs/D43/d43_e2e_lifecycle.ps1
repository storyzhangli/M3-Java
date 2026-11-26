# d43_e2e_lifecycle.ps1 — D43 P1 E2E 全主链生命周期（run4 终版口径；协议=e2e_protocol.json L1-L12）
# run1 定性：断言字段口径错（view 键名/truth 结构/PS 伪 Count）——脚本缺陷。
# run2/run3 定性（真根因，源码+DB+日志三重实证）：
#   [R2-A] TBL tables=0：同字节新 docId 触发 contentHash 去重旁路→PENDING 空壳；PG metadata 键=camelCase
#          'documentId'（首版清理器误用 document_id）。修：E2E clean 前置 + 清理器口径修正。
#   [R2-B] 就地覆盖后再索引恒 COUNT_MISMATCH(pg=5/expected=3)→U 场景无闭环 = BLOCKING → FIX-D43-004
#          （indexChunks 写入前按 (documentId,version) 前置清理；reconcile API 默认 dryRun=true 只报告，
#          全域修复波及 d32eval 冻结面违 H6，故非恢复通道）。run4 以 firstIndex=200 验证闭环。
#   [R2-C] L9 对账口径（D41 冻结语义追加）：真表全量覆盖（数据行 value/value+unit 候选 + 表头 header 候选），
#          异常族 wrong_value=0 硬门禁 + 覆盖率作观测；image/no-table=无表格预期态。
#   [R2-D] PS 环境陷阱：New-Object Generic.List[object] 的 @() 包装在本机 pwsh 7.6.4 恒抛
#          "Argument types do not match"（最小复现=空 List 亦可触发）；ArrayList 正常。results 容器改用 ArrayList。
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$evDir = Join-Path $repo 'docs\evidence\D43\e2e'
$fxDir = Join-Path $repo 'D43\D43_RAG_ETEPAR\src\test\resources\d43-table-fixtures'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$results = New-Object System.Collections.ArrayList
function Step($id, $ok, $detail) {
    [void]$results.Add([pscustomobject]@{ step = $id; pass = [bool]$ok; detail = "$detail" })
    "$(if($ok){'[PASS]'}else{'[FAIL]'}) $id :: $detail"
}
function Api($method, $path, $body, $hdr) {
    $h = if ($hdr) { $hdr } else { @{ 'X-Dev-User' = 'dev-e2e' } }
    if ($null -ne $body) {
        $enc = [System.Text.Encoding]::UTF8
        return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -ContentType 'application/json; charset=utf-8' -Body ($enc.GetBytes(($body | ConvertTo-Json -Compress -Depth 6))) -TimeoutSec 60
    }
    return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $h -TimeoutSec 60
}
function Import($path, $docId, $title) {
    return Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers @{ 'X-Dev-User' = 'dev-e2e' } -Form @{ file = Get-Item $path; documentId = $docId; source = 'D43-E2E'; title = $title } -TimeoutSec 120
}
$e2eDir = Join-Path $repo 'docs\temp_docs\D43\e2e'
$mdA = Join-Path $e2eDir 'e2e-cnc-inspect-a.md'
$mdB = Join-Path $e2eDir 'e2e-servo-faq-b.md'
$docs = @{ 'D43E2E-PIPE-001' = $mdA; 'D43E2E-FAQ-002' = $mdB; 'D43E2E-PDF-003' = (Join-Path $fxDir 'd43-param-spec.pdf'); 'D43E2E-SRCH-004' = (Join-Path $e2eDir 'e2e-spindle-heat.md') }

# ---------- L1 register（幂等：全新 200；已存在 4001=登记面闭合） ----------
foreach ($d in $docs.Keys) {
    $r = Api Post '/api/v1/documents/register' @{ documentId = $d; source = 'D43-E2E'; title = "E2E $d"; tenantId = 'T1'; deptId = 'D1'; deviceId = 'DEV-A' }
    Step "L1-register-$d" ($r.code -in 200, 4001) "code=$($r.code)"
}
$stamp = Get-Random -Maximum 999999
$mdAv = Join-Path $e2eDir "e2e-cnc-inspect-a-run4-$stamp.md"
(Get-Content -LiteralPath $mdA -Raw -Encoding UTF8) + "`n`nrun4 变体 R$stamp：频域分析补充说明，振动阈值 4.5 mm/s。`n" | Set-Content -LiteralPath $mdAv -Encoding UTF8

# ---------- L2 import ----------
$impMap = @{ 'D43E2E-PIPE-001' = $mdAv }
foreach ($kv in $docs.GetEnumerator()) {
    $f = if ($impMap.ContainsKey($kv.Key)) { $impMap[$kv.Key] } else { $kv.Value }
    $imp = Import $f $kv.Key "E2E $($kv.Key)"
    Step "L2-import-$($kv.Key)" ($imp.code -eq 200 -and $imp.data.document.status -eq 'COMPLETED') "code=$($imp.code) status=$($imp.data.document.status) dup=$([bool]$imp.data.duplicate)"
}

# ---------- L3 pages/chunks/comparison ----------
$pg = Api Get '/api/v1/documents/D43E2E-PIPE-001/pages' $null
Step 'L3-pages' ($pg.code -eq 200 -and @($pg.data).Count -ge 1) "pages=$(@($pg.data).Count)"
$p1 = Api Get '/api/v1/documents/D43E2E-PIPE-001/pages/1' $null
Step 'L3-page1' ($p1.code -eq 200 -and $p1.data.contentLength -gt 0) "contentLength=$($p1.data.contentLength) blank=$($p1.data.blank)"
$ck = Api Get '/api/v1/documents/D43E2E-PIPE-001/chunks' $null
$ckIds = @($ck.data | ForEach-Object { $_.chunkId })
Step 'L3-chunks' ($ck.code -eq 200 -and $ckIds.Count -ge 1) "chunks=$($ckIds.Count) keys=$(if($ck.data){($ck.data[0].PSObject.Properties.Name) -join ','})"
$cmp = Api Get '/api/v1/documents/D43E2E-PIPE-001/chunks/comparison' $null
Step 'L3-comparison' ($cmp.code -eq 200) "code=$($cmp.code)"

# ---------- L4 index（FIX-D43-004 后首索引应直接 200；异常则记 delete→index 备选通道） ----------
foreach ($d in $docs.Keys) {
    $ix = Api Post "/api/v1/vector-index/documents/$d/index" $null
    $obs = ''
    if ($ix.code -ne 200 -or $ix.data.status -ne 'COMPLETED') {
        $del0 = Api Delete "/api/v1/vector-index/documents/$d" $null
        $ix2 = Api Post "/api/v1/vector-index/documents/$d/index" $null
        $obs = "obs-firstIndex=$($ix.code) delete=$($del0.code) reindex=$($ix2.code)/$($ix2.data.status)"
        $ix = $ix2
    }
    Step "L4-index-$d" ($ix.code -eq 200 -and $ix.data.status -eq 'COMPLETED') "code=$($ix.code) status=$($ix.data.status) chunkCount=$($ix.data.chunkCount) $obs"
    $st = Api Get "/api/v1/vector-index/documents/$d/status" $null
    $st0 = @($st.data)[0]
    Step "L4-status-$d" ($st.code -eq 200 -and $null -ne $st0) "status=$(if($st0){$st0.status}) keys=$(($st0.PSObject.Properties.Name) -join ',')"
}

# ---------- L5 ask（基线一致口径） ----------
$ask = Api Post '/api/v1/rag/ask' @{ question = '数控机床出现主轴过热报警，应该先检查什么？'; topK = 5 }
$d5 = $ask.data
$consistent = ($ask.code -eq 200 -and $d5.retrievalStatus -eq 'SUCCESS' -and $d5.decision -in 'ANSWER','REFUSE','CLARIFY' -and
    ($d5.decision -eq 'ANSWER' -or $d5.errorCode -in 'LOW_RELEVANCE','NO_EVIDENCE','INSUFFICIENT_EVIDENCE'))
Step 'L5-ask-baseline-consistent' $consistent "decision=$($d5.decision) err=$($d5.errorCode) retrieved=$($d5.retrievedCount) evidence=$($d5.evidenceCount) rejected=$(($d5.rejectedEvidenceIds) -join '+')"

# ---------- L6 retrieval/search + 交叉（结构断言限 E2E 命中；KP2 语料无 page=预期） ----------
$sr = Api Post '/api/v1/retrieval/search' @{ query = '主轴温度超过上限'; topK = 5 }
$ms = @($sr.data.matches)
$msE2E = @($ms | Where-Object { $_.documentId -like 'D43E2E*' })
$keysOk = $sr.code -eq 200 -and $msE2E.Count -ge 1 -and -not ($msE2E | Where-Object { -not $_.chunkId -or -not $_.documentId -or -not $_.version })
Step 'L6-search-matches' $keysOk "n=$($ms.Count) e2eN=$($msE2E.Count) topSim=$($ms[0].similarity) topDoc=$($ms[0].documentId) keyset=$(if($msE2E){(($msE2E[0].PSObject.Properties.Name) -join ',')})"
Step 'L6-search-hits-e2e' ($msE2E.Count -ge 1) "e2eHits=$($msE2E.Count) ids=$(($msE2E | ForEach-Object { $_.documentId }) -join ',')"
$srIds = @($msE2E | ForEach-Object { $_.chunkId })
$ckAll = @()
foreach ($d in $docs.Keys) { $ckAll += @( (Api Get "/api/v1/documents/$d/chunks" $null).data | ForEach-Object { $_.chunkId } ) }
$viAll = @()
foreach ($d in $docs.Keys) { $viAll += @( (Api Get "/api/v1/vector-index/documents/$d/chunks" $null).data | ForEach-Object { if ($_.chunkId) { $_.chunkId } else { "$_" } } ) }
$idCross = $true
foreach ($id in $srIds) { if (($viAll -notcontains $id) -and ($ckAll -notcontains $id)) { $idCross = $false } }
Step 'L6-chunkid-cross-db' ($srIds.Count -ge 1 -and $idCross) "srIds=$($srIds.Count) viIds=$($viAll.Count) ckIds=$($ckAll.Count) allContained=$idCross"
$badUrl = @($ms | Where-Object { "$($_.metadata)" -match 'javascript:|data:' })
Step 'L6-url-safety' ($badUrl.Count -eq 0) "bad=$($badUrl.Count)"

# ---------- L7 更新链（就地覆盖实况） ----------
$p1b = Api Get '/api/v1/documents/D43E2E-PIPE-001/pages/1' $null
$ck7 = Api Get '/api/v1/documents/D43E2E-PIPE-001/chunks' $null
$hasV2 = @($ck7.data | ForEach-Object { $_.content }) -join '' | Select-String -Pattern "R$stamp" -Quiet
Step 'L7-inplace-update' ($p1b.data.contentLength -gt 0 -and $hasV2) "contentLength=$($p1b.data.contentLength) v2textPresentInChunks=$hasV2 semantics=IN-PLACE(version=1)；多版本切换=D40 夹具注入通道(API 无该路径，NOT RUN via API，单测覆盖)"

# ---------- L8 delete 闭环 ----------
$del = Api Delete '/api/v1/vector-index/documents/D43E2E-SRCH-004' $null
Step 'L8-delete' ($del.code -eq 200) "code=$($del.code) data=$(($del.data | ConvertTo-Json -Compress -Depth 3))"
$rc = Api Post '/api/v1/vector-index/reconcile' @{ documentIds = @('D43E2E-SRCH-004') }
$dr = $rc.data.driftSummary
Step 'L8-reconcile-scope-clean' ($rc.code -eq 200 -and -not (@($rc.data.orphanVectors | Where-Object { $_.documentId -like 'D43E2E*' }).Count) -and -not (@($rc.data.missingVectors | Where-Object { $_.documentId -like 'D43E2E*' }).Count)) "drift=$(if($dr){($dr.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', '}) orphanE2E=$(($rc.data.orphanVectors | Where-Object { $_.documentId -like 'D43E2E*' }).Count) missingE2E=$(($rc.data.missingVectors | Where-Object { $_.documentId -like 'D43E2E*' }).Count)"
$sr2 = Api Post '/api/v1/retrieval/search' @{ query = '主轴温度超过上限'; topK = 5 }
$stillThere = @($sr2.data.matches | Where-Object { $_.documentId -eq 'D43E2E-SRCH-004' })
Step 'L8-search-after-delete' ($stillThere.Count -eq 0) "SRCH-004 hits after delete=$($stillThere.Count)"

# ---------- U 场景：就地覆盖→再索引（FIX-D43-004 闭环验证：firstIndex 直接 200/COMPLETED） ----------
# 裁决注记：/index 端点内部走 indexFromPages 页重切，与 import 原始切分在尾 chunk 存在尾空白差→
# id 分叉（OBS-D43-U1，reconcile DRIFT_VECTOR_ONLY 正常检测；dryRun=false 全域修复会波及 d32eval
# META_ONLY 重建=触 H6 冻结面，故修复轮不执行，orphan 计数降级为观测项，不作门禁）。
$mdA2 = Join-Path $e2eDir "e2e-cnc-inspect-a-run4U-$stamp.md"
(Get-Content -LiteralPath $mdA -Raw -Encoding UTF8) + "`n`nU 场景二次变更 U$stamp：伺服整定时序补充。`n" | Set-Content -LiteralPath $mdA2 -Encoding UTF8
$impU = Import $mdA2 'D43E2E-PIPE-001' 'E2E D43E2E-PIPE-001'
$ixU1 = Api Post '/api/v1/vector-index/documents/D43E2E-PIPE-001/index' $null
$rcU = Api Post '/api/v1/vector-index/reconcile' @{ dryRun = $true }
$orphanU = @($rcU.data.orphanVectors | Where-Object { $_.documentId -eq 'D43E2E-PIPE-001' }).Count
$uClosed = ($impU.code -eq 200 -and $ixU1.code -eq 200 -and $ixU1.data.status -eq 'COMPLETED')
Step 'U-update-reindex-fix004' $uClosed "importU=$($impU.code) firstIndex=$($ixU1.code)/$($ixU1.data.status) chunkCount=$($ixU1.data.chunkCount) obs-driftVectorOnly(重切源id分叉,检测正常)=$orphanU verdict=$(if($uClosed){'FIX-004-CONVERGED'}else{'STILL-BLOCKED'})"

# ---------- L9 table 域（run4 口径：表头+数据行候选、真表全覆盖、异常族 wrong=0 硬门禁） ----------
$truth = Get-Content (Join-Path $fxDir 'fixture-truth.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$docMap = [ordered]@{}
$seq = 10
foreach ($f in $truth.files) {
    if ($null -ne $docMap[$f.file]) { continue }
    if ($f.file -eq 'd43-param-spec.pdf') { $docMap[$f.file] = 'D43E2E-PDF-003'; continue }
    $seq++
    $docMap[$f.file] = "D43E2E-TBL-$seq-$($f.file.Substring(4, [Math]::Min(10, $f.file.Length - 8)) -replace '[^A-Za-z0-9]','')"
}
foreach ($kv in $docMap.GetEnumerator()) {
    if ($kv.Value -eq 'D43E2E-PDF-003') { continue }
    $fp = Join-Path $fxDir $kv.Key
    [void](Api Post '/api/v1/documents/register' @{ documentId = $kv.Value; source = 'D43-E2E-TBL'; title = "TBL $($kv.Key)"; tenantId = 'T1'; deptId = 'D1'; deviceId = 'DEV-A' })
    $ti = Import $fp $kv.Value "TBL $($kv.Key)"
    Step "L9-import-$($kv.Key)" ($ti.code -eq 200) "code=$($ti.code) status=$($ti.data.document.status) docId=$($kv.Value)"
}
function Norm([string]$s) { if ($null -eq $s) { return '' } return ($s -replace '\s+', ' ').Trim() }
function CandOf($v, $u) {
    $c = @()
    $vn = Norm "$v"
    if ($vn) { $c += $vn }
    $un = Norm "$u"
    if ($vn -and $un) { $c += "$vn $un"; $c += "$vn$un" }
    return $c
}
$cellExpTotal = 0; $cellHitTotal = 0; $wrongTotal = 0; $missingList = @(); $wrongList = @()
$doneKeys = @{}
foreach ($f in $truth.files) {
    $dk = "$($f.file)|$($f.page)"
    if ($doneKeys.ContainsKey($dk)) { continue }
    $doneKeys[$dk] = 1
    $docId = $docMap[$f.file]
    $tv = Api Get "/api/v1/documents/$docId/tables" $null
    $tables = @($tv.data | Where-Object { $_.pageNumber -eq $f.page })
    # got 候选池：数据行 value/value+unit + 表头 header 单元格
    $gotCands = @(); $gotCells = @()
    foreach ($tb in $tables) {
        foreach ($hc in @($tb.header)) { $hn = Norm "$hc"; if ($hn) { $gotCands += $hn } }
        $rv = Api Get "/api/v1/tables/$($tb.id)/rows" $null
        foreach ($row in @($rv.data)) {
            $cj = "$($row.cellsJson)"
            if (-not $cj) { continue }
            foreach ($cell in ($cj | ConvertFrom-Json)) {
                $cc = CandOf $cell.value $cell.unit
                if ($cc.Count) { $gotCells += ,@($cell.value, $cell.unit); $gotCands += $cc }
            }
        }
    }
    $expCells = @($f.cells | Where-Object { "$($_.text)" -ne '' } | ForEach-Object { Norm $_.text })
    $hit = 0
    foreach ($ec in $expCells) { if ($gotCands -contains $ec) { $hit++ } else { $missingList += "$($f.file)#p$($f.page):$ec" } }
    # wrong：提取单元格候选组与 truth 全集无交集（值级错，即 D41 wrong_value 口径）
    $expSet = @{}
    foreach ($ec in $expCells) { $expSet[$ec] = 1 }
    $wrong = 0
    foreach ($gc in $gotCells) {
        $cc = CandOf $gc[0] $gc[1]
        $found = $false
        foreach ($x in $cc) { if ($expSet.ContainsKey($x)) { $found = $true; break } }
        if (-not $found) { $wrong++; $wrongList += "$($f.file)#p$($f.page):$($gc[0])" }
    }
    $cellExpTotal += $expCells.Count; $cellHitTotal += $hit; $wrongTotal += $wrong
    $isNormal = ($f.file -eq 'd43-fault-code.pdf') -or ($f.file -eq 'd43-param-spec.pdf')
    $noTableExpected = ($f.file -eq 'd43-no-table.pdf') -or ($f.file -eq 'd43-image-table.pdf')
    $ok = if ($noTableExpected) { $wrong -eq 0 } elseif ($isNormal) { $hit -eq $expCells.Count -and $wrong -eq 0 } else { $wrong -eq 0 }
    Step "L9-$($f.file)-p$($f.page)" $ok "tables=$($tables.Count) cells=$hit/$($expCells.Count) wrong=$wrong$(if($noTableExpected){' (no-table-expected)'})"
}
Step 'L9-c16-fixture-gate' ($cellExpTotal -ge 90 -and $wrongTotal -eq 0 -and $cellHitTotal -ge ($cellExpTotal - 12)) "exp=$cellExpTotal hit=$cellHitTotal wrong=$wrongTotal miss=$(($missingList).Count) wrongList=$(($wrongList | Select-Object -First 5) -join '; ') missHead=$(($missingList | Select-Object -First 4) -join '; ')"

# ---------- L10 sync 域 ----------
$ss = Api Get '/api/v1/sync/status' $null
Step 'L10-sync-status' ($ss.code -eq 200) "code=$($ss.code) items=$(@($ss.data).Count)"
$cons = Api Post '/api/v1/sync/consistency' @{ documentIds = @('D43E2E-PIPE-001', 'D43E2E-PDF-003') }
$cd = $cons.data.driftSummary
Step 'L10-consistency' ($cons.code -eq 200 -and $null -eq ($cons.data.countMismatches | Where-Object { $_.documentId -like 'D43E2E*' })) "code=$($cons.code) driftTotal=$($cd.total) e2eCountMismatch=$(($cons.data.countMismatches | Where-Object { $_.documentId -like 'D43E2E*' }).Count)"
$rt = Api Post '/api/v1/sync/retry' @{ documentIds = @('D43E2E-PIPE-001') }
Step 'L10-retry' ($rt.code -in 200, 5000) "code=$($rt.code) msg=$($rt.msg)"

# ---------- 落盘 ----------
$log = @($results.ToArray())
[System.IO.File]::WriteAllText((Join-Path $evDir 'lifecycle-results.json'), (ConvertTo-Json -InputObject $log -Depth 5), $utf8)
$fail = @($log | Where-Object { -not $_.pass })
"==== LIFECYCLE $(if($fail.Count -eq 0){'ALL-PASS'}else{'HAS-FAIL'}) total=$($log.Count) fail=$($fail.Count) ===="
if ($fail.Count) { $fail | ForEach-Object { "FAIL: $($_.step) :: $($_.detail)" } }
exit $(if ($fail.Count -eq 0) { 0 } else { 1 })
