# d39_fixture-cleanup.ps1 — D39-R01 P6/C19 夹具清理与三处核验（enabled=false 实例）
# 顺序：DELETE 端点×6 → DB 残留核验（PG/MySQL 主表+索引表）→ 残留则 DB 级清理夹具行 → 指纹复算须=10fc69d5…
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$out = Join-Path $repo "docs\evidence\D39\cleanup"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$log = [System.Collections.Generic.List[string]]::new()
function Say($m) { $log.Add($m); Write-Output $m }

$docIds = @("DOC-D39-F1","DOC-D39-F2","DOC-D39-F3","DOC-D39-F4","DOC-D39-F5","DOC-D39-F6")

# 1) 删除端点×6（VectorIndexController delete，enabled=false 无收口，直接可用）
foreach ($d in $docIds) {
    try {
        $r = Invoke-RestMethod "$base/api/v1/vector-index/documents/$d" -Method Delete -TimeoutSec 60
        Say ("[DELETE-API] {0} code={1}" -f $d, $r.code)
    } catch { Say ("[DELETE-API-ERR] {0} {1}" -f $d, $_.Exception.Message) }
}
[System.IO.File]::WriteAllLines("$out\cleanup-api.log", $log, $utf8)

# 2) DB 残留核验 + 必要时 DB 级清理（仅夹具行：documentId IN 6 / source=fixture-access）
$pw = [Environment]::GetEnvironmentVariable('PG_VECTOR_PASSWORD', 'User')
$env:D39_PG_PW = $pw; $env:D39_MY_PW = '123456'
try {
    & pwsh -NoProfile -File (Join-Path $repo "docs\temp_docs\D39\d39_cleanup-verify.ps1")
    Say "[VERIFY] harness exit=$LASTEXITCODE"
} finally { Remove-Item Env:D39_PG_PW -ErrorAction SilentlyContinue; Remove-Item Env:D39_MY_PW -ErrorAction SilentlyContinue }
Say "[DONE] 清理与核验完成，详见 $out"
