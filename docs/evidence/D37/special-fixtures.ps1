# D37 特殊夹具用例（基线冻结后执行，避免污染无过滤基线）
# C13-生命周期 fail-closed：夹具文档无 MySQL 行 → NOT_FOUND → REFUSE
# CONFLICT 专项：同标题两片段“额定压力 0.8/1.2” → FactClaimExtractor 可抽取 → CONFLICT
# 仅操作本迭代夹具 documentId，用后 DELETE 清理。
param([string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$evDir = Join-Path $RepoRoot "docs\evidence\D37\special-cases"
$utf8 = New-Object System.Text.UTF8Encoding($false)

function PostJson($url, $obj, $timeout = 90) {
    $json = $obj | ConvertTo-Json -Depth 8
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Uri $url -Method Post -Body $bytes -ContentType "application/json; charset=utf-8" -TimeoutSec $timeout
}

$results = [ordered]@{}

# ---- CONFLICT 夹具 ----
$conflictId = "D37-EVAL-CONFLICT-901"
$conflictTitle = "D37评测冲突夹具压力容器"
# 先登记（knowledge_document 落 ACTIVE 行），否则生命周期过滤先于冲突判定短路为 NO_EVIDENCE
$reg = PostJson "$base/api/v1/documents/register" @{ source = "MANUAL"; title = $conflictTitle; documentId = $conflictId }
Write-Output ("[CONFLICT-REGISTER] code=" + $reg.code + " documentId=" + $reg.data.documentId)
$req = @{ documentId = $conflictId; version = 1; strategy = "STRUCTURE"; title = $conflictTitle; chunks = @(
    @{ chunkIndex = 0; content = "该压力容器铭牌记录：额定压力 0.8 MPa，出厂检验合格。"; globalStart = 0; globalEnd = 30 },
    @{ chunkIndex = 1; content = "该压力容器改造记录：额定压力 1.2 MPa，改造后重新登记。"; globalStart = 30; globalEnd = 60 }
) }
$idx = PostJson "$base/api/v1/vector-index/index" $req
Write-Output ("[CONFLICT-INDEX] code=" + $idx.code + " status=" + $idx.data.status + " chunks=" + $idx.data.chunkCount)
$ask = PostJson "$base/api/v1/rag/ask" @{ question = "该压力容器的额定压力是多少？"; topK = 5; documentId = $conflictId }
$d = $ask.data
Write-Output ("[CONFLICT-ASK] decision=" + $d.decision + " errorCode=" + $d.errorCode + " conflictGroups=" + @($d.conflictGroups).Count + " retrieved=" + $d.retrievedCount + " evidence=" + $d.evidenceCount)
$results["conflict"] = @{ register = $reg; index = $idx; ask = $ask }

# ---- 生命周期 fail-closed 夹具（无 MySQL knowledge_document 行）----
$lifeId = "D37-EVAL-LIFECYCLE-902"
$req2 = @{ documentId = $lifeId; version = 1; strategy = "STRUCTURE"; title = "D37评测生命周期夹具"; chunks = @(
    @{ chunkIndex = 0; content = "夹具内容：主轴过热报警时应先检查冷却液液位与冷却泵运行声音。"; globalStart = 0; globalEnd = 32 }
) }
$idx2 = PostJson "$base/api/v1/vector-index/index" $req2
Write-Output ("[LIFECYCLE-INDEX] code=" + $idx2.code + " status=" + $idx2.data.status)
$ask2 = PostJson "$base/api/v1/rag/ask" @{ question = "主轴过热报警应该先检查什么？"; topK = 5; documentId = $lifeId }
$d2 = $ask2.data
Write-Output ("[LIFECYCLE-ASK] decision=" + $d2.decision + " errorCode=" + $d2.errorCode + " retrieved=" + $d2.retrievedCount + " evidence=" + $d2.evidenceCount + " rejected=" + (@($d2.rejectedEvidenceIds).Count))
$results["lifecycle"] = @{ index = $idx2; ask = $ask2 }

# ---- 清理夹具（仅本迭代夹具 documentId）----
foreach ($id in @($conflictId, $lifeId)) {
    $del = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id" -Method Delete -TimeoutSec 30
    $ch = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$id/chunks" -TimeoutSec 15
    Write-Output ("[CLEANUP] $id removed=" + $del.data + " remaining=" + @($ch.data).Count)
    $results["cleanup_$id"] = @{ removed = $del.data; remaining = @($ch.data).Count }
}

[System.IO.File]::WriteAllText((Join-Path $evDir "special-fixtures-results.json"), ($results | ConvertTo-Json -Depth 10), $utf8)
Write-Output "[DONE] 夹具用例完成，证据 special-fixtures-results.json"
