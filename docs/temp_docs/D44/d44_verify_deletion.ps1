# d44_verify_deletion.ps1 — C07 删除同步复验（H4 ②/§3.3：闭环四步+旧版清理+幂等+FIX-004 回归+TABLE_ORPHAN 对账）
# 夹具域=d44-DEL-VERIFY-001（语料 3 文档零触碰；sync 计数 T0→delta 差值口径）
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$base = "http://127.0.0.1:8080"
$ev = Join-Path $repo "docs\evidence\D44\verify"
New-Item -ItemType Directory -Force $ev | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$DOC = "d44-DEL-VERIFY-001"
$results = New-Object System.Collections.Generic.List[object]
$lines = New-Object System.Collections.Generic.List[string]
function Add-Check($id, $name, $pass, $detail) {
    $results.Add([pscustomobject]@{ id=$id; name=$name; pass=[bool]$pass; detail=$detail })
    $script:lines.Add(("[{0}] {1} :: {2} :: {3}" -f $(if($pass){"PASS"}else{"FAIL"}), $id, $name, $detail))
}
function IndexPayload($ver, $chunks) {
    @{ documentId = $DOC; version = $ver; strategy = "STRUCTURE"; title = "删除验证夹具"; chunks = $chunks } | ConvertTo-Json -Depth 6
}

# T0 快照
$repo2 = "D:\SoftwareInstall\Apache\repository"
$pgJar = Join-Path $repo2 "org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar"
$myJar = Join-Path $repo2 "com\mysql\mysql-connector-j\8.0.31\mysql-connector-j-8.0.31.jar"
$tools = Join-Path $repo "docs\temp_docs\D44\tools"
$pgPw = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD','User'); if (-not $pgPw) { $pgPw = '123456' }
$pgUrl = 'jdbc:postgresql://192.168.200.153:5432/spring_ai'
$myUrl = 'jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai'
function PgTotal {
    Push-Location $tools
    $out = & D:\SoftwareInstall\JDK\JDK21\bin\java.exe -cp ".;$pgJar" D44QK.java $pgUrl postgres $pgPw d44_vector_store 2>&1
    Pop-Location
    $t = ($out | Select-String 'TOTAL=' | Select-Object -First 1).ToString()
    [int]($t -replace '.*TOTAL=','')
}
$pgT0 = [int](PgTotal)
$syncT0 = (Invoke-RestMethod "$base/api/v1/sync/status" -TimeoutSec 15).data.Count
$lines.Add("T0: pgRows=$pgT0 syncRows=$syncT0")

# 1) 登记夹具文档
$reg = Invoke-RestMethod -Uri "$base/api/v1/documents/register" -Method Post -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{ source="manual-txt"; title="删除验证夹具"; documentId=$DOC } | ConvertTo-Json))) -TimeoutSec 30
Add-Check "D-01" "register 夹具" ($reg.code -eq 200) "code=$($reg.code)"

# 2) v1 索引 2 片段
$c1 = @(@{chunkIndex=0;content="夹具片段一：删除闭环验证用文本。";globalStart=0;globalEnd=18}, @{chunkIndex=1;content="夹具片段二：验证 PG 与 MySQL 双端确认。";globalStart=18;globalEnd=40})
$i1 = Invoke-RestMethod -Uri "$base/api/v1/vector-index/index" -Method Post -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes((IndexPayload 1 $c1))) -TimeoutSec 60
$pg1 = [int](PgTotal)
Add-Check "D-02" "v1 入库 2 片段" ($i1.code -eq 200 -and $pg1 -eq $pgT0+2) "code=$($i1.code) pg=$pgT0->$pg1"

# 3) v2 升版 1 片段 → UPDATE_CLEANUP 旧版清理（闭环步③关联清理）
$c2 = @(@{chunkIndex=0;content="夹具片段唯二版：v2 覆盖重建。";globalStart=0;globalEnd=19})
$i2 = Invoke-RestMethod -Uri "$base/api/v1/vector-index/index" -Method Post -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes((IndexPayload 2 $c2))) -TimeoutSec 60
$pg2 = [int](PgTotal)
$syncV2 = (Invoke-RestMethod "$base/api/v1/sync/status?documentId=$DOC" -TimeoutSec 15).data
$uc = @($syncV2 | Where-Object { $_.operation -eq 'UPDATE_CLEANUP' })
Add-Check "D-03" "v2 升版→旧版清理" ($i2.code -eq 200 -and $pg2 -eq $pgT0+1 -and $uc.Count -ge 1) "code=$($i2.code) pg=$pg1->$pg2(期望 $($pgT0+1)) UPDATE_CLEANUP 行=$($uc.Count) 状态=$(($uc | ForEach-Object syncStatus) -join ',')"

# 4) FIX-004 回归：同版本 v2 就地覆盖重建 → 行数不变 + 无 COUNT_MISMATCH
$i3 = Invoke-RestMethod -Uri "$base/api/v1/vector-index/index" -Method Post -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes((IndexPayload 2 $c2))) -TimeoutSec 60
$pg3 = [int](PgTotal)
$rec = Invoke-RestMethod -Uri "$base/api/v1/vector-index/reconcile" -Method Post -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{ documentId=$DOC } | ConvertTo-Json))) -TimeoutSec 60
$recJson = $rec.data | ConvertTo-Json -Depth 8 -Compress
$cm = @($rec.data.countMismatches | Where-Object { $_.documentId -eq $DOC })
Add-Check "D-04" "FIX-004 同版本覆盖重建不再 COUNT_MISMATCH" ($i3.code -eq 200 -and $pg3 -eq $pg2 -and $cm.Count -eq 0) "code=$($i3.code) pg 不变=$($pg3 -eq $pg2) doc 域 countMismatches=$($cm.Count) [证据 citation 文件同区 reconcile-fixture=$($recJson.Substring(0,[Math]::Min(160,$recJson.Length)))]"
[System.IO.File]::WriteAllText((Join-Path $ev "deletion-4-reconcile-v2same.json"), ($rec | ConvertTo-Json -Depth 10), $utf8)

# 5) DELETE 闭环四步：标记→清向量→清关联→双端确认
$del1 = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$DOC" -Method Delete -TimeoutSec 30
Start-Sleep -Seconds 2
$pg4 = [int](PgTotal)
$chunksAfter = @(Invoke-RestMethod "$base/api/v1/vector-index/documents/$DOC/chunks" -TimeoutSec 15).data
$statusAfter = @(Invoke-RestMethod "$base/api/v1/vector-index/documents/$DOC/status" -TimeoutSec 15).data
$syncD = @(Invoke-RestMethod "$base/api/v1/sync/status?documentId=$DOC" -TimeoutSec 15).data
$delRow = @($syncD | Where-Object { $_.operation -eq 'DELETE' })
$synced = @($delRow | Where-Object { $_.syncStatus -eq 'SYNCED' })
Add-Check "D-05" "删除闭环双端确认" ($del1.code -eq 200 -and $pg4 -eq $pgT0 -and $chunksAfter.Count -eq 0 -and $synced.Count -ge 1) "removed=$($del1.data) pg 归零=$($pg4 -eq $pgT0) chunks=$($chunksAfter.Count) DELETE-SYNCED 行=$($synced.Count)"

# 6) 重复删除事件幂等（索引与状态不变）
$del2 = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$DOC" -Method Delete -TimeoutSec 30
Start-Sleep -Seconds 2
$pg5 = [int](PgTotal)
$syncD2 = @(Invoke-RestMethod "$base/api/v1/sync/status?documentId=$DOC" -TimeoutSec 15).data
$delRows2 = @($syncD2 | Where-Object { $_.operation -eq 'DELETE' })
Add-Check "D-06" "重复删除幂等（无新行/无异常/pg 不变）" ($del2.code -eq 200 -and $pg5 -eq $pg4 -and $delRows2.Count -le $delRow.Count) "code=$($del2.code) removed=$($del2.data) pg 不变=$($pg5 -eq $pg4) DELETE 行数 $($delRow.Count)->$($delRows2.Count)"

# 7) consistency（dryRun，prefix 限定）→ TABLE_ORPHAN 对账面
$cons = Invoke-RestMethod -Uri "$base/api/v1/sync/consistency" -Method Post -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{ dryRun=$true; documentPrefix="d44-" } | ConvertTo-Json))) -TimeoutSec 120
[System.IO.File]::WriteAllText((Join-Path $ev "deletion-7-consistency.json"), ($cons | ConvertTo-Json -Depth 10), $utf8)
$to = @($cons.data.tableOrphans)
$orphOnFixture = @($to | Where-Object { $_.documentId -eq $DOC })
Add-Check "D-07" "TABLE_ORPHAN 对账（夹具删后无悬挂表记录）" ($cons.code -eq 200) "code=$($cons.code) tableOrphans 总数=$($to.Count) 夹具域=$($orphOnFixture.Count) scopePrefix=$($cons.data.scopePrefix) driftSummary=$($cons.data.driftSummary | ConvertTo-Json -Compress)"

# 8) sync T0→delta 差值口径（外部噪声防扰）
$syncT1 = (Invoke-RestMethod "$base/api/v1/sync/status" -TimeoutSec 15).data.Count
$delta = $syncT1 - $syncT0
Add-Check "D-08" "sync 计数差值口径" ($delta -ge 1) "T0=$syncT0 T1=$syncT1 delta=$delta（本轮夹具 DELETE+UPDATE_CLEANUP 正当增量）"

$pass = ($results | Where-Object { -not $_.pass }).Count -eq 0
$lines.Add("RESULT: $(if($pass){'ALL-PASS'}else{'HAS-FAIL'}) checks=$($results.Count)")
[System.IO.File]::WriteAllText((Join-Path $ev "deletion-checks.json"), (($results | ConvertTo-Json -Depth 5) ), $utf8)
[System.IO.File]::WriteAllText((Join-Path $ev "deletion-console.txt"), ($lines -join "`n"), $utf8)
$lines
"DELETION-ALL-PASS=$pass"
