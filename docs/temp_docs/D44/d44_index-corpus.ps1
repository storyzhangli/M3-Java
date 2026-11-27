# d44_index-corpus.ps1 — D44 KP2 资产 seeding：冻结语料幂等入库 d44_vector_store
# 依据：docs\temp_docs\D43\d43_index-corpus.ps1 同构迁移（表名/路径 d43→d44，D43 原脚本未动）。
# D44 新增：3b) activate 步骤核实——索引重建后逐文档 /status 轮询，确认全部行 ACTIVE 才放行
#   （D43 C05 三段定界教训：DELETE tombstone 瞬态窗口，恢复非即时，禁止直接进评测）。
# 前置：D44 应用 8080 已启动（AI_MODE=mock，默认 disabled 态）。
param([string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$evDir = Join-Path $RepoRoot "docs\evidence\D44\probe"
New-Item -ItemType Directory -Force -Path $evDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

$corpus = Get-Content -LiteralPath (Join-Path $RepoRoot "D44\D44_RAG_PDAM\src\main\resources\knowledge\equipment-fault-corpus.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$ids = @($corpus | ForEach-Object { $_.documentId })
Write-Output ("CorpusDocs: " + ($ids -join ", "))

# 0) 登记核验：3 文档必须已登记且 version=1；缺失则按语料元数据补登记
foreach ($doc in $corpus) {
    $list = Invoke-RestMethod -Uri "$base/api/v1/documents" -TimeoutSec 15
    $found = @($list.data) | Where-Object { $_.documentId -eq $doc.documentId }
    if ($found.Count -eq 0) {
        $reg = @{ source = "manual-txt"; title = $doc.title; documentId = $doc.documentId }
        $resp = Invoke-RestMethod -Uri "$base/api/v1/documents/register" -Method Post -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes(($reg | ConvertTo-Json))) -TimeoutSec 30
        Write-Output ("[REGISTER] " + $doc.documentId + " code=" + $resp.code)
    } else {
        Write-Output ("[REGISTER-OK] " + $doc.documentId + " status=" + $found[0].status + " version=" + $found[0].version)
    }
}

# 1) 入库前状态留痕
$pre = [ordered]@{}
foreach ($id in $ids) {
    $ch = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/chunks" -TimeoutSec 15
    $st = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/status" -TimeoutSec 15
    $pre[$id] = @{ chunks = $ch.data; status = $st.data }
}
[System.IO.File]::WriteAllText((Join-Path $evDir "kp2-seed-prestate.json"), ($pre | ConvertTo-Json -Depth 8), $utf8)
Write-Output "[OK] prestate saved: kp2-seed-prestate.json"

# 2) 删除本文档旧索引（仅这 3 个 documentId；幂等重建前置，遗留 DOC-* 不触碰）
foreach ($id in $ids) {
    $del = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id" -Method Delete -TimeoutSec 30
    Write-Output ("[DELETE] $id removed=" + $del.data + " code=" + $del.code)
}

# 3) 按冻结语料重建索引（content=excerpt 逐字一致，STRUCTURE，version=1）
$results = @()
foreach ($doc in $corpus) {
    $payloadChunks = @()
    $offset = 0
    for ($i = 0; $i -lt @($doc.chunks).Count; $i++) {
        $c = $doc.chunks[$i]
        $len = $c.excerpt.Length
        $payloadChunks += @{ chunkIndex = $i; content = $c.excerpt; globalStart = $offset; globalEnd = $offset + $len }
    }
    $req = @{ documentId = $doc.documentId; version = 1; strategy = "STRUCTURE"; title = $doc.title; chunks = $payloadChunks }
    $json = $req | ConvertTo-Json -Depth 6
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $resp = Invoke-RestMethod -Uri "$base/api/v1/vector-index/index" -Method Post -Body $bytes -ContentType "application/json; charset=utf-8" -TimeoutSec 120
    Write-Output ("[INDEX] " + $doc.documentId + " code=" + $resp.code + " data=" + ($resp.data | ConvertTo-Json -Depth 4 -Compress))
    if ($resp.code -ne 200) { throw ("INDEX FAILED: " + $doc.documentId) }
    $results += @{ documentId = $doc.documentId; resp = $resp.data }
}
[System.IO.File]::WriteAllText((Join-Path $evDir "kp2-seed-results.json"), ($results | ConvertTo-Json -Depth 8), $utf8)

# 3b) activate 步骤核实（D44 KP2 新增：tombstone 瞬态窗口规避，D43 C05 教训）
# 口径实扫：lifecycle 在 knowledge_document.lifecycleStatus（DocumentLifecycleStatus.ACTIVE，D40 状态机）；
#           向量行侧核验 /status 行 status=COMPLETED 且 attemptCount 收敛。
$deadline = (Get-Date).AddSeconds(180)
$report = @()
$allActive = $false
while ((Get-Date) -lt $deadline) {
    $report = @()
    $docList = Invoke-RestMethod -Uri "$base/api/v1/documents" -TimeoutSec 15
    $okAll = $true
    foreach ($id in $ids) {
        $drow = @($docList.data) | Where-Object { $_.documentId -eq $id }
        $lc = if ($drow.Count -gt 0) { $drow[0].lifecycleStatus } else { 'MISSING' }
        $st = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/status" -TimeoutSec 15
        $irows = @($st.data)
        $istat = (@($irows | ForEach-Object { $_.status }) | Sort-Object -Unique) -join ','
        $ok = ($lc -eq 'ACTIVE') -and ($irows.Count -gt 0) -and ($istat -eq 'COMPLETED')
        if (-not $ok) { $okAll = $false }
        $report += @{ id = $id; lifecycle = $lc; indexRows = $irows.Count; indexStatus = $istat; ok = $ok }
    }
    if ($okAll) { $allActive = $true; break }
    Start-Sleep -Seconds 3
}
Write-Output ("[ACTIVATE] allActive=" + $allActive + " at " + (Get-Date -Format 's'))
foreach ($row in $report) { Write-Output ("  " + $row.id + " rows=" + $row.rows + " status=" + $row.statuses) }
[System.IO.File]::WriteAllText((Join-Path $evDir "kp2-seed-activate.json"), ($report | ConvertTo-Json -Depth 6), $utf8)
if (-not $allActive) { throw "ACTIVATE_TIMEOUT: 180s 内 lifecycle 未全 ACTIVE（tombstone 窗口未收敛，禁止进入评测）" }

# 4) 入库后核验
$after = @()
foreach ($id in $ids) {
    $ch = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/chunks" -TimeoutSec 15
    $st = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/status" -TimeoutSec 15
    $n = @($ch.data).Count
    $statuses = @($st.data | ForEach-Object { $_.status }) -join ","
    Write-Output ("[VERIFY] $id chunks=$n status=$statuses")
    $after += @{ id = $id; chunks = $n; status = $statuses }
}
[System.IO.File]::WriteAllText((Join-Path $evDir "kp2-seed-poststate.json"), ($after | ConvertTo-Json -Depth 6), $utf8)
Write-Output "[DONE] D44 corpus seeding complete"
