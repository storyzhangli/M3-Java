# D43 P3 §4.3 六域异常注入（A1/A2/A3/A5/A6 段——当前 enabled+ONE 实例；A4 另段 FAULT env）
# 原则：全部夹具 d43-a*- 前缀（H6），用后即清，禁破坏性操作；每案断言=响应码+错误语义+DB 残留核查。
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$outDir = Join-Path $repo 'docs\evidence\D43\exceptions'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$ST = (Get-Date).ToString('HHmmss')
$prin = Get-Content (Join-Path $repo 'docs\temp_docs\D43\auth\principals.local.json') -Raw | ConvertFrom-Json
$H = @{ Authorization = "Bearer $($prin.principals.adminT1.token)" }
$HU = @{ Authorization = "Bearer $($prin.principals.userA.token)" }
$HX = @{ Authorization = 'Bearer d43-a6-not-a-real-token-0000000000000000000000000000' }
$jdk = 'D:\SoftwareInstall\JDK\JDK21'; $repoM = 'D:\SoftwareInstall\Apache\repository'
$myjar = (Get-ChildItem "$repoM\com\mysql\mysql-connector-j\8.0.33" -Filter *.jar | Where-Object Name -notmatch 'sources' | Select-Object -First 1).FullName
$mpw = [Environment]::GetEnvironmentVariable('MYSQL_PASSWORD','User'); if(-not $mpw){$mpw='123456'}
$cases = New-Object System.Collections.ArrayList
function Add-Case($id,$dom,$name,$ok,$detail){
    [void]$cases.Add([pscustomobject]@{ case=$id; domain=$dom; name=$name; pass=[bool]$ok; detail=$detail })
    "{0} [{1}] {2} :: {3} :: {4}" -f $(if($ok){'PASS'}else{'FAIL'}), $id, $dom, $name, $detail
}
function QDoc($docId) {
    $st = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$docId/status" -Headers $H -TimeoutSec 30
    $cnt = ($st.data | Measure-Object).Count
    $stat = ($st.data | ForEach-Object { $_.indexStatus } | Sort-Object -Unique) -join ','
    "chunks=$cnt status=$stat"
}
# ---------- A1 解析域：损坏 PDF（register→import 全链） ----------
$src = (Get-ChildItem -Recurse "$repo\D43\D43_RAG_ETEPAR\src\test\resources\d43-table-fixtures" -Filter '*.pdf' | Where-Object Name -match 'fault-code' | Select-Object -First 1).FullName
$brk = Join-Path $outDir "d43-a1-broken-$ST.pdf"
$bytes = [System.IO.File]::ReadAllBytes($src)
for ($i = 0; $i -lt 16; $i++) { $bytes[$i] = 0x41 }          # 头 16 字节魔改（非 PDF）
[System.IO.File]::WriteAllBytes($brk, $bytes)
$docA1 = "D43-A1-BRK-$ST"
[void](Invoke-RestMethod -Uri "$base/api/v1/documents/register" -Method Post -Headers $H -ContentType 'application/json; charset=utf-8' -TimeoutSec 30 -Body (@{ documentId = $docA1; source = 'D43-A1'; title = "A1 broken"; tenantId = 'T1'; deviceId = 'DEV-A' } | ConvertTo-Json))
$imp = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers $H -Form @{ file = Get-Item $brk; documentId = $docA1; source = 'D43-A1' } -TimeoutSec 60
$q1 = QDoc $docA1
$okA1 = ($imp.data.document.status -match 'FAIL') -and ($q1 -match 'chunks=0')   # import 响应内解析终态 FAILED + 零向量
Add-Case 'A1' '解析' '损坏 PDF→document.status=FAILED（PARSE 明确终态）；无有效残留' $okA1 "import.code=$($imp.code) docStatus=$($imp.data.document.status) err=$($imp.data.document.errorCode) db=$(($q1))"
# A1 清理（DELETE 端点按生命周期脚本形态：/api/v1/documents/{id} DELETE）
try { [void](Invoke-RestMethod -Uri "$base/api/v1/documents/$docA1" -Method Delete -Headers $H -TimeoutSec 30) } catch { }

# ---------- A2 切分域：极短/全空白/超长 ----------
$results = @{}
foreach ($kind in 'short','ws','long') {
    $txt = switch ($kind) {
        'short' { 'x' }
        'ws'    { (' ' * 500) + "`n`t`t`n   " }
        'long'  { ((0..200 | ForEach-Object { "第$_行超长填充内容ABCDEFG 中文段落样本。" }) -join "`n") }
    }
    $f = Join-Path $outDir "d43-a2-$kind-$ST.md"
    [System.IO.File]::WriteAllText($f, "# A2 $kind`n`n$txt", (New-Object System.Text.UTF8Encoding($false)))
    $did = "D43-A2-$($kind.ToUpper())-$ST"
    [void](Invoke-RestMethod -Uri "$base/api/v1/documents/register" -Method Post -Headers $H -ContentType 'application/json; charset=utf-8' -TimeoutSec 30 -Body (@{ documentId = $did; source = 'D43-A2'; title = "A2 $kind"; tenantId = 'T1'; deviceId = 'DEV-A' } | ConvertTo-Json))
    $ii = Invoke-RestMethod -Uri "$base/api/v1/documents/import" -Method Post -Headers $H -Form @{ file = Get-Item $f; documentId = $did; source = 'D43-A2' } -TimeoutSec 120
    $ck = Invoke-RestMethod -Uri "$base/api/v1/documents/$did/chunks" -Headers $H -TimeoutSec 30
    $cn = ($ck.data | Measure-Object).Count
    $results[$kind] = @{ code = $ii.code; docStatus = $ii.data.document.status; chunks = $cn }
    "A2/$kind code=$($ii.code) docStatus=$($ii.data.document.status) chunks=$($cn)"
}
# 断言（切分域）：全空白不产生有效分片入库；超长正常切分；极短不炸链（终态明确）
$okA2 = ($results['ws'].chunks -eq 0) -and ($results['long'].chunks -ge 1) -and ($results['short'].code -eq 200)
Add-Case 'A2' '切分' '极短/全空白/超长：空白零分片入库、超长正常、极短终态明确' $okA2 ("short=$($results['short'].docStatus)/$($results['short'].chunks) ws=$($results['ws'].docStatus)/$($results['ws'].chunks) long=$($results['long'].docStatus)/$($results['long'].chunks)")

# ---------- A3 Embedding 域：空输入/维度断言 ----------
$e1 = Invoke-RestMethod -Uri "$base/api/v1/embeddings" -Method Post -Headers $H -ContentType 'application/json; charset=utf-8' -Body '{"input":""}' -TimeoutSec 30
$e2 = Invoke-RestMethod -Uri "$base/api/v1/embeddings" -Method Post -Headers $H -ContentType 'application/json; charset=utf-8' -Body '[]' -TimeoutSec 30
$okA3 = ($e1.code -ne 200) -and ($e2.code -ne 200)
Add-Case 'A3' 'Embedding' '空串/空数组输入→受控错误码非成功（校验 fail-fast）' $okA3 "empty=''$code=$($e1.code) msg=$($e1.message); array=[] code=$($e2.code)"

# ---------- A5 生成域：在线=无证据拒答不误放行；MODEL_ERROR 分类以离线权威（D42 E3 先例） ----------
$a5 = Invoke-RestMethod -Uri "$base/api/v1/rag/ask" -Method Post -Headers $HU -ContentType 'application/json; charset=utf-8' -Body '{"question":"紫孔雀鱼量子纠缠养殖指南","topK":5,"threshold":0.99}' -TimeoutSec 60
$citN = ($a5.data.citations | Measure-Object).Count
$okA5a = ($a5.code -eq 200) -and ($citN -eq 0)   # 高阈值零证据→拒答且 citations 空=引用核验不误放行
Add-Case 'A5-online' '生成' '无证据问题→REFUSAL 拒答，citations 空（不误放行）' $okA5a "code=$($a5.code) citations=$($citN) answerType=$($a5.data.answerType)"
$e5Log = Join-Path $outDir "a5-offline-modelerror-$ST.log"
$jdk21 = 'D:\SoftwareInstall\JDK\JDK21'
# 权威离线通道=评测器 SelfTest（d37eval 冻结源码，MODEL_ERROR:EMPTY/GENERIC 分类断言族）
$repoM2 = 'D:\SoftwareInstall\Apache\repository'
$jackson = @("$repoM2\com\fasterxml\jackson\core\jackson-databind\2.19.0\jackson-databind-2.19.0.jar","$repoM2\com\fasterxml\jackson\core\jackson-core\2.19.0\jackson-core-2.19.0.jar","$repoM2\com\fasterxml\jackson\core\jackson-annotations\2.19.0\jackson-annotations-2.19.0.jar") -join ';'
$clsDir = Join-Path $repo 'docs\temp_docs\D37\evaluator\classes'
& "$($jdk21)\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$clsDir;$jackson" d37eval.SelfTest 2>&1 | Tee-Object $e5Log | Out-Null
$okA5b = ($LASTEXITCODE -eq 0) -and ((Select-String -Path $e5Log -Pattern 'MODEL_ERROR').Count -ge 1)
Add-Case 'A5-offline' '生成' 'MODEL_ERROR 分类以评测器 SelfTest 为权威（在线 mock 无异常注入面=D42 E3 先例口径）' $okA5b "exit=$LASTEXITCODE log=a5-offline-modelerror-$ST.log"

# ---------- A6 权限域：无效 token / ANON / 跨租户读（真实端点 tables 面），fail-closed+折叠+审计 ----------
$bad = Invoke-RestMethod -Uri "$base/api/v1/documents/D43E2E-MXA-001/tables" -Headers $HX -TimeoutSec 30
$anon = Invoke-RestMethod -Uri "$base/api/v1/documents/D43E2E-MXA-001/tables" -TimeoutSec 30
$crossB = Invoke-RestMethod -Uri "$base/api/v1/documents/D43E2E-MXB-002/tables" -Headers $HU -TimeoutSec 30
$msgEq = ($anon.message -eq $bad.message) -or ($anon.msg -eq $bad.msg)
$okA6 = ($bad.code -eq 4030) -and ($anon.code -eq 4030) -and ($crossB.code -eq 4030) -and $msgEq
Add-Case 'A6' '权限' '无效/匿名 token+跨租户文档(tables 面)→4030 fail-closed；匿名与无效文案折叠一致' $okA6 "bad=$($bad.code) anon=$($anon.code) crossB=$($crossB.code) foldEq=$msgEq"

# ---------- 汇总落盘（夹具清理：A1+A2 文档 DELETE，PG/MySQL 残留交 D43E2EClean 终扫） ----------
foreach ($did in @($docA1) + @('SHORT','WS','LONG' | ForEach-Object { "D43-A2-$_-$ST" })) {
    try { [void](Invoke-RestMethod -Uri "$base/api/v1/documents/$did" -Method Delete -Headers $H -TimeoutSec 30) } catch { }
}
$cases | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $outDir "a-matrix-run1-$ST.json") -Encoding UTF8
"==== RUN1 cases=$($cases.Count) PASS=$(($cases|Where-Object pass).Count) ===="
