# d42_index-corpus.ps1 — D42 KP2 资产 seeding：冻结语料幂等入库 d42_vector_store
# 背景：KP2 实扫 d42_vector_store=0 行（initialize-schema=true 新表；D39/D40/D41 同型先例）。
# 依据：docs\temp_docs\D41\d41_index-corpus.ps1 模式改编（先 DELETE 本 3 文档旧索引再 STRUCTURE 重建；
#       chunkId=UUIDv5 确定性；仅操作语料内 3 个 documentId，遗留 DOC-* 行不触碰）。
# 前置：D42 应用 8080 已启动（AI_MODE=mock，rerank 缺席默认态）。
param([string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$evDir = Join-Path $RepoRoot "docs\evidence\D42\probe"
New-Item -ItemType Directory -Force -Path $evDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

$corpus = Get-Content -LiteralPath (Join-Path $RepoRoot "D42\D42_RERANK\src\main\resources\knowledge\equipment-fault-corpus.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$ids = @($corpus | ForEach-Object { $_.documentId })
Write-Output ("语料文档: " + ($ids -join ", "))

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
Write-Output "[OK] 入库前状态已留痕 kp2-seed-prestate.json"

# 2) 删除本文档旧索引（仅这 3 个 documentId；幂等重建前置）
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
    if ($resp.code -ne 200) { throw ("入库失败: " + $doc.documentId) }
    $results += @{ documentId = $doc.documentId; resp = $resp.data }
}
[System.IO.File]::WriteAllText((Join-Path $evDir "kp2-seed-results.json"), ($results | ConvertTo-Json -Depth 8), $utf8)

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
Write-Output "[DONE] D42 语料 seeding 完成"
