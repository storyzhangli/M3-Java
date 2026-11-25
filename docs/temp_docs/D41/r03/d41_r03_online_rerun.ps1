# D41-R03 在线闭环复跑 v2（勘因：同字节再导入被 contentHash 去重拦截 → 验证夹具加 r03 尾注
#   使哈希分离走真实导入路；表结构取证改用 MySQL 原生客户端）
$ErrorActionPreference = 'Stop'
$root = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$docId = 'D41-R03RE-013'
$mdDocId = 'D41-R03RE-014'
$outDir = Join-Path $root 'docs\evidence\D41\R03'
$fxDir = Join-Path $root 'docs\temp_docs\D41\r03\fixtures'
New-Item -ItemType Directory -Force -Path $fxDir | Out-Null
$fxPdf = Join-Path $fxDir 'crosspage-r03v1.pdf'
$fxMd  = Join-Path $fxDir 'pipe-continuation-r03v1.md'

# 0) 生成 r03 变体夹具（字节级=原夹具+尾注，语义级=原夹具内容完整保留）
$srcPdf = Join-Path $root 'docs\temp_docs\D41\r01\tools\crosspage-r01v3.pdf'
[IO.File]::WriteAllBytes($fxPdf, (@([IO.File]::ReadAllBytes($srcPdf)) + [Text.Encoding]::ASCII.GetBytes("`n%%D41-R03-ONLINE-RERUN-V3`n")) )
$srcMd = Join-Path $root 'docs\temp_docs\D41\r01\tools\pipe-continuation-r01v2.md'
Copy-Item $srcMd $fxMd -Force
Add-Content -Path $fxMd -Value "`n<!-- D41-R03 online rerun v3 marker -->" -Encoding UTF8
"SHA fxPdf=" + (Get-FileHash $fxPdf -Algorithm SHA256).Hash.Substring(0,16)
"SHA fxMd =" + (Get-FileHash $fxMd -Algorithm SHA256).Hash.Substring(0,16)

$tokens = Get-Content (Join-Path $root 'docs\temp_docs\D41\auth\fixture-tokens.json') -Raw | ConvertFrom-Json
$adminHeader = @{ Authorization = "Bearer $($tokens.principals.adminA1.token)" }
$r = [ordered]@{ ts=(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'); codeBaseline='2f55b10+工作区R03解析整改'; instance='r5'; notes='夹具 r03 变体避 contentHash 去重（去重语义本身为既定设计，见 R01 文档）'; steps=@() }
function Step($name,$ok,$detail){ $r.steps += [ordered]@{ step=$name; pass=[bool]$ok; detail=$detail }; ("{0} {1} :: {2}" -f ($(if($ok){'PASS'}else{'FAIL'})),$name,$detail) }
function RowCount($d){ if($null -eq $d){0}else{@($d).Count} }

# MySQL 取证（D41DbQuery 先例：口令经 MYSQL_PWD 环境变量注入，禁明文）
$yml = Get-Content 'D41\D41_TASIIEM\src\main\resources\application.yml' -Raw
$m = [regex]::Match($yml, 'password: \$\{MYSQL_PASSWORD:([^}]*)\}')
if (-not $env:MYSQL_PWD) { $env:MYSQL_PWD = $m.Groups[1].Value }
$tools = Join-Path $root 'docs\temp_docs\D41\probe\tools'
$jar = (Get-ChildItem 'D:\SoftwareInstall\Apache\repository\com\mysql\mysql-connector-j\8.0.33' -Filter *.jar | Select-Object -First 1).FullName
function Sql($q){ & 'D:\SoftwareInstall\JDK\JDK21\bin\java.exe' '-Dfile.encoding=UTF-8' -cp "$tools;$jar" D41DbQuery $q 2>&1 | Out-String }

# 1) 导入 r03 变体 PDF（真实导入路：全新字节 → 钩子首轮 extract+persist）
$null = Invoke-RestMethod -Uri "$base/api/v1/documents/register" -Method Post -Headers $adminHeader -ContentType 'application/json' -Body (@{ documentId=$docId; source='D41-R03-VERIFY-PDF'; title='R03闭环复跑跨页夹具v1'; tenantId='T1'; deptId='D-HQ' } | ConvertTo-Json)
$imp = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers $adminHeader -Form @{ file=Get-Item $fxPdf; documentId=$docId; source='D41-R03-VERIFY-PDF'; title='R03闭环复跑跨页夹具v1' }
$dup = $imp.data.duplicate
Step 'import-pdf' ((-not $dup) -and $imp.code -eq 200 -and $imp.data.document.status -eq 'COMPLETED') "code=$($imp.code) duplicate=$dup status=$($imp.data.document.status)"

# 2) 首轮表：p2 表回链 p1 表 ID（页序谓词正例）+ 表详情行记录
$tv1 = Invoke-RestMethod -Uri "$base/api/v1/documents/$docId/tables" -Headers $adminHeader
$t1 = $tv1.data | Where-Object pageNumber -eq 1 | Select-Object -First 1
$t2 = $tv1.data | Where-Object pageNumber -eq 2 | Select-Object -First 1
Step 'first-round-link' ($t1 -and $t2 -and $t2.continuationOfTableId -eq $t1.id) "p1Id=$($t1.id) p2Cont=$($t2.continuationOfTableId)"
$rd1 = Invoke-RestMethod -Uri "$base/api/v1/tables/$($t1.id)/rows" -Headers $adminHeader
Step 'first-round-rows' ((RowCount $rd1.data) -ge 2) "rows=$(RowCount $rd1.data)"

# 3) 二轮 POST extract 触发 reExtract（旧 ID 全换=双轨偏移）
$ex2 = Invoke-RestMethod -Uri "$base/api/v1/documents/$docId/tables/extract" -Method Post -Headers $adminHeader -ContentType 'application/json' -Body '{}'
$tv2 = Invoke-RestMethod -Uri "$base/api/v1/documents/$docId/tables" -Headers $adminHeader
$n1 = $tv2.data | Where-Object pageNumber -eq 1 | Select-Object -First 1
$n2 = $tv2.data | Where-Object pageNumber -eq 2 | Select-Object -First 1
Step 'reextract-ids-rotated' ($n1 -and $n2 -and $n1.id -ne $t1.id -and $n2.id -ne $t2.id) "old=($($t1.id),$($t2.id)) new=($($n1.id),$($n2.id))"
Step 'reextract-link-remapped' ($n2 -and $n1 -and $n2.continuationOfTableId -eq $n1.id) "newCont=$($n2.continuationOfTableId) expect=$($n1.id) leaked=$($n2.continuationOfTableId -eq $t1.id)"

# 4) SQL 终态：悬空=0 / 行数一致 / 旧行 ID 清零
function SqlNum($q){ $s = Sql $q; $ls = @($s -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^\d+$' }); if ($ls.Count -gt 0) { [int]$ls[0] } else { -1 } }
$dangling = SqlNum "SELECT COUNT(*) FROM d41_table_extract te LEFT JOIN d41_table_extract p ON te.continuation_of_table_id=p.id WHERE te.continuation_of_table_id IS NOT NULL AND p.id IS NULL"
$oldGone  = SqlNum "SELECT COUNT(*) FROM d41_table_row WHERE table_id IN ($($t1.id),$($t2.id))"
$rc = Sql "SELECT te.row_count, (SELECT COUNT(*) FROM d41_table_row tr WHERE tr.table_id=te.id) AS actual FROM d41_table_extract te WHERE te.document_id='$docId'"
$rowPairs = @($rc -split "`r?`n" | ForEach-Object { if ($_ -match '^\s*(\d+)\D+(\d+)\s*$') { [pscustomobject]@{ expect=[int]$Matches[1]; actual=[int]$Matches[2] } } })
$rowConsistent = ($rowPairs.Count -ge 2) -and (@($rowPairs | Where-Object { $_.expect -ne $_.actual }).Count -eq 0)
Step 'sql-dangling-zero' ($dangling -eq 0) "dangling=$dangling"
Step 'sql-old-rows-cleared' ($oldGone -eq 0) "rowsOnOldIds=$oldGone"
Step 'sql-rowcount-consistent' $rowConsistent "pairs=$(($rowPairs | ForEach-Object { "$($_.expect)/$($_.actual)" }) -join ',')"

# 5) 同页反例：r03 变体 md 真实导入 → 两张同页同表头表不链
$null = Invoke-RestMethod -Uri "$base/api/v1/documents/register" -Method Post -Headers $adminHeader -ContentType 'application/json' -Body (@{ documentId=$mdDocId; source='D41-R03-VERIFY'; title='R03同页反例v1'; tenantId='T1'; deptId='D-HQ' } | ConvertTo-Json)
$imp2 = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers $adminHeader -Form @{ file=Get-Item $fxMd; documentId=$mdDocId; source='D41-R03-VERIFY'; title='R03同页反例v1' }
$tvM = Invoke-RestMethod -Uri "$base/api/v1/documents/$mdDocId/tables" -Headers $adminHeader
$noLink = $imp2.data.duplicate -eq $false -and (RowCount $tvM.data) -ge 2 -and (@($tvM.data | Where-Object { $null -ne $_.continuationOfTableId }).Count -eq 0)
Step 'samepage-counterexample-nolink-http' $noLink "dup=$($imp2.data.duplicate) tables=$(RowCount $tvM.data) linked=$(RowCount ($tvM.data | Where-Object { $null -ne $_.continuationOfTableId }))"

# 5b) 4030 越权语义：R01 online-verify 十步先例无此步——语义由 D41-P3 C01 权限单测覆盖，
#     本复跑不越界新增断言（userB1 夹具归属 T1 同租户，200 属正确行为，非缺陷）。

# 6) 删除闭环清场（本切片只清 011/012；R01 证据文档 V-006/007 不动）
$del1 = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$docId" -Method Delete -Headers $adminHeader
$del2 = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$mdDocId" -Method Delete -Headers $adminHeader
Start-Sleep -Seconds 2
$tvA = Invoke-RestMethod -Uri "$base/api/v1/documents/$docId/tables" -Headers $adminHeader
$tvB = Invoke-RestMethod -Uri "$base/api/v1/documents/$mdDocId/tables" -Headers $adminHeader
Step 'delete-clears-tables' ((RowCount $tvA.data) -eq 0 -and (RowCount $tvB.data) -eq 0) "delA=$($del1.code) delB=$($del2.code) remain=$((RowCount $tvA.data)+(RowCount $tvB.data))"
$dangling2 = SqlNum "SELECT COUNT(*) FROM d41_table_extract te LEFT JOIN d41_table_extract p ON te.continuation_of_table_id=p.id WHERE te.continuation_of_table_id IS NOT NULL AND p.id IS NULL"
$resid = SqlNum "SELECT COUNT(*) FROM d41_table_extract WHERE document_id IN ('$docId','$mdDocId')"
Step 'sql-clean-after-delete' (($dangling2 -eq 0) -and ($resid -eq 0)) "dangling=$dangling2 residual=$resid"

$r | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $outDir 'online-verify-r03rerun.json') -Encoding UTF8
$fail = @($r.steps | Where-Object { -not $_.pass }).Count
"OVERALL=$(if($fail -eq 0){'ALL-PASS'}else{"FAIL=$fail"}) EVIDENCE=$(Join-Path $outDir 'online-verify-r03rerun.json')"


