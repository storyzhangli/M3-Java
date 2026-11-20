# D37 语料幂等入库脚本：仅操作本迭代 3 个夹具 documentId
# 前置：应用已启动（8080）。旧 FIXED_SIZE 部分索引与冻结语料不一致，
# 按自然键(doc_id,version,strategy,chunk_index)语义，先 DELETE 本文档旧索引再重建 STRUCTURE 索引。
# 历史残留（d32eval-* 等）只读，不触碰。
param([string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$evDir = Join-Path $RepoRoot "docs\evidence\D37\special-cases"
New-Item -ItemType Directory -Force -Path $evDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

$corpus = Get-Content -LiteralPath (Join-Path $RepoRoot "D37\D37_RAG_ESAB\src\main\resources\knowledge\equipment-fault-corpus.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$ids = @($corpus | ForEach-Object { $_.documentId })
Write-Output ("语料文档: " + ($ids -join ", "))

# 1) 入库前状态留痕
$pre = [ordered]@{}
foreach ($id in $ids) {
    $ch = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/chunks" -TimeoutSec 15
    $st = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/status" -TimeoutSec 15
    $pre[$id] = @{ chunks = $ch.data; status = $st.data }
}
[System.IO.File]::WriteAllText((Join-Path $evDir "index-prestate.json"), ($pre | ConvertTo-Json -Depth 8), $utf8)
Write-Output "[OK] 入库前状态已留痕 index-prestate.json"

# 2) 删除本迭代文档旧索引（仅这 3 个 documentId）
foreach ($id in $ids) {
    $del = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id" -Method Delete -TimeoutSec 30
    Write-Output ("[DELETE] $id removed=" + $del.data + " code=" + $del.code)
}

# 3) 按冻结语料重建索引（content=excerpt 逐字一致，STRUCTURE，version=1）
foreach ($doc in $corpus) {
    $payloadChunks = @()
    $offset = 0
    for ($i = 0; $i -lt @($doc.chunks).Count; $i++) {
        $c = $doc.chunks[$i]
        $len = $c.excerpt.Length
        $payloadChunks += @{ chunkIndex = $i; content = $c.excerpt; globalStart = $offset; globalEnd = $offset + $len }
        $offset += $len
    }
    $req = @{ documentId = $doc.documentId; version = 1; strategy = "STRUCTURE"; title = $doc.title; chunks = $payloadChunks }
    $json = $req | ConvertTo-Json -Depth 6
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $resp = Invoke-RestMethod -Uri "$base/api/v1/vector-index/index" -Method Post -Body $bytes -ContentType "application/json; charset=utf-8" -TimeoutSec 120
    Write-Output ("[INDEX] " + $doc.documentId + " code=" + $resp.code + " data=" + ($resp.data | ConvertTo-Json -Depth 4 -Compress))
    if ($resp.code -ne 200) { throw ("入库失败: " + $doc.documentId) }
}

# 4) 入库后核验
foreach ($id in $ids) {
    $ch = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/chunks" -TimeoutSec 15
    $st = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/status" -TimeoutSec 15
    $n = @($ch.data).Count
    $statuses = @($st.data | ForEach-Object { $_.status }) -join ","
    Write-Output ("[VERIFY] $id chunks=$n status=$statuses")
}
Write-Output "[DONE] 语料入库完成"
