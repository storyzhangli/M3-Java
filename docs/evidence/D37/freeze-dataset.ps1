# D37 数据集冻结脚本：盖章 annotatedAt + 生成 SHA-256 manifest（先冻结后评测）
# 用法: pwsh -File freeze-dataset.ps1 [-RepoRoot <root>]
param(
    [string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java"
)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$datasetRel = "docs/evidence/D37/dataset/rag-evaluation-questions.v1.0.jsonl"
$datasetPath = Join-Path $RepoRoot $datasetRel
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# 1) 盖章 annotatedAt（仅 null → 当前时间；已冻结则保持原值，禁止覆盖）
$lines = [System.IO.File]::ReadAllLines($datasetPath, [System.Text.Encoding]::UTF8)
$stamp = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
$stamped = 0
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '"annotatedAt":null') {
        $lines[$i] = $lines[$i] -replace '"annotatedAt":null', ('"annotatedAt":"' + $stamp + '"')
        $stamped++
    }
}
if ($stamped -gt 0) {
    [System.IO.File]::WriteAllLines($datasetPath, $lines, $utf8NoBom)
    Write-Output "[FREEZE] 已盖章 $stamped 条 annotatedAt=$stamp"
} else {
    Write-Output "[FREEZE] 数据集已冻结，保持原 annotatedAt 不变"
}

# 2) 哈希清单（相对仓库根，正斜杠）
$files = @(
    $datasetRel,
    "D37/D37_RAG_ESAB/src/main/resources/knowledge/equipment-fault-corpus.json",
    "docs/temp_docs/D37/evaluator/evaluator-config.json",
    "D37/D37_RAG_ESAB/src/main/java/com/zl/d37/rag/citation/UrlSafetyPolicy.java"
)
$files += Get-ChildItem -LiteralPath (Join-Path $RepoRoot "docs/temp_docs/D37/evaluator/src/d37eval") -Filter *.java |
    ForEach-Object { "docs/temp_docs/D37/evaluator/src/d37eval/" + $_.Name }

$hashes = [ordered]@{}
foreach ($rel in $files) {
    $abs = Join-Path $RepoRoot ($rel -replace '/', '\')
    if (-not (Test-Path -LiteralPath $abs)) { throw "冻结清单文件缺失: $rel" }
    $hashes[$rel] = (Get-FileHash -LiteralPath $abs -Algorithm SHA256).Hash.ToLowerInvariant()
}

$gitHead = (git -C $RepoRoot rev-parse HEAD).Trim()
$manifest = [ordered]@{
    datasetVersion = "v1.0"
    frozenAt       = $stamp
    gitHead        = $gitHead
    embedding      = [ordered]@{
        provider = "local"; model = "local-hashing-embedding"; modelVersion = "local-v1"
        dimensions = 384; distanceMetric = "COSINE"
    }
    discipline     = "先冻结后评测；看结果反改标注必须升版本并写变更说明；本清单不可变。"
    files          = $hashes
}
$manifestPath = Join-Path $RepoRoot "docs/evidence/D37/dataset/dataset-manifest.json"
$json = $manifest | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($manifestPath, $json, $utf8NoBom)
Write-Output "[FREEZE] manifest 已写入: $manifestPath（$($hashes.Count) 个文件哈希）"
