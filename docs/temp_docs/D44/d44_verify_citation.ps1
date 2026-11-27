# d44_verify_citation.ps1 — C06 引用真实（H4 三层之②在线级 + ③数据级 + 边界声明）
# 前置：D44 baseline 实例 8080（disabled），KP2 seeding 完成（8 片段 ACTIVE）
# 层①服务级=CitationTruthDefenseTest（mvn 执行，证据另存）；本脚本覆盖②③。
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$base = "http://127.0.0.1:8080"
$ev = Join-Path $repo "docs\evidence\D44\verify"
New-Item -ItemType Directory -Force $ev | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$report = [ordered]@{ at = (Get-Date -Format 's'); layer2_online = $null; layer3_data = $null; boundary = $null }

# ---- 层② 在线级：mock ask 有证据题 → 契约字段齐全；citations 不误放行 ----
$ask = Invoke-RestMethod -Uri "$base/api/v1/rag/ask" -Method Post -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{ question = "CNC冷却系统压力异常怎么处理" } | ConvertTo-Json))) -TimeoutSec 60
$answer = if ($ask.data.answer) { $ask.data.answer } elseif ($ask.data.reply) { $ask.data.reply } else { $ask.data | ConvertTo-Json -Depth 2 -Compress }
$srcCount = 0
if ($ask.data.sources) { $srcCount = @($ask.data.sources).Count }
$citCount = 0
if ($ask.data.citations) { $citCount = @($ask.data.citations).Count }
$markerInAnswer = ($answer -match '\[S\d')
$layer2 = [ordered]@{
  code = $ask.code; decision = $ask.data.decision; hasAnswer = ($null -ne $ask.data.answer -or $null -ne $ask.data.reply)
  sourcesCount = $srcCount; citationsCount = $citCount
  citationMarkerInAnswer = $markerInAnswer
  assertion = if ($srcCount -eq 0 -and $citCount -eq 0 -and -not $markerInAnswer) { "PASS: mock 态 citations=0 且无 [S] 标记泄漏（不误放行，D43 C12 口径）" } else { "REVIEW: src=$srcCount cit=$citCount marker=$markerInAnswer" }
  rawFields = @($ask.data.PSObject.Properties.Name)
}
$report.layer2_online = $layer2
$layer2.assertion
# 响应 jsonl 留痕
[System.IO.File]::WriteAllText((Join-Path $ev "citation-2-online.json"), ($ask | ConvertTo-Json -Depth 10), $utf8)

# ---- 层③ 数据级：检索命中 chunkId × document_chunk_index × PG 行存在性对账 ----
$hits = Invoke-RestMethod -Uri "$base/api/v1/retrieval/search" -Method Post -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{ query = "CNC冷却系统压力异常"; topK = 5 } | ConvertTo-Json))) -TimeoutSec 60
$chunkIds = @($hits.data.matches | ForEach-Object { $_.chunkId }) | Where-Object { $_ }
$repo2 = "D:\SoftwareInstall\Apache\repository"
$pgJar = Join-Path $repo2 "org\postgresql\postgresql\42.7.11\postgresql-42.7.11.jar"
$myJar = Join-Path $repo2 "com\mysql\mysql-connector-j\8.0.31\mysql-connector-j-8.0.31.jar"
$tools = Join-Path $repo "docs\temp_docs\D44\tools"
$pgPw = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD','User'); if (-not $pgPw) { $pgPw = '123456' }
Push-Location $tools
foreach ($cid in $chunkIds) {
    & D:\SoftwareInstall\JDK\JDK21\bin\java.exe -cp ".;$pgJar;$myJar" D44ChunkReconcile.java `
        'jdbc:postgresql://192.168.200.153:5432/spring_ai' postgres $pgPw d44_vector_store `
        'jdbc:mysql://127.0.0.1:3306/ai_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' root 123456 $cid 2>&1 |
        Add-Content (Join-Path $ev "citation-3-data-reconcile.txt")
}
Pop-Location
$report.layer3_data = @{ hitCount = $chunkIds.Count; detailFile = "citation-3-data-reconcile.txt" }

# ---- 边界声明（H4：禁冒充） ----
$report.boundary = "模型级「引用真实」NOT RUN：mock 态 sources 恒空（D43 C12），层①②③为结构断言+数据对账，不构成端到端模型引用验证；真实臂须授权，列优化清单 OPT-D44-3"

[System.IO.File]::WriteAllText((Join-Path $ev "citation-1-layers-summary.json"), ($report | ConvertTo-Json -Depth 8), $utf8)
"[DONE] C06 layers: L2=$($layer2.assertion); L3 hits=$($chunkIds.Count); boundary registered"
