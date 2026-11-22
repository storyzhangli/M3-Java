# ============================================================================
# D39-R01 #240 步骤A：夹具清理（DELETE API×6 → verify → 必要时 apply → final verify）
# 前置：enabled=false 实例在线。证据：docs\evidence\D39\cleanup\
# ============================================================================
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$repo = "E:\AI\project\AI-Learning-one\M3-Java"
$out = Join-Path $repo "docs\evidence\D39\cleanup"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
$docIds = @("DOC-D39-F1","DOC-D39-F2","DOC-D39-F3","DOC-D39-F4","DOC-D39-F5","DOC-D39-F6")
$log = [System.Collections.Generic.List[string]]::new()
function Say($m) { $log.Add("$(Get-Date -Format HH:mm:ss) $m"); Write-Output $m }

# 1) 删除端点×6（沿用先例：DELETE /api/v1/vector-index/documents/{id}，先删向量再核 MySQL）
foreach ($d in $docIds) {
    try {
        $r = Invoke-RestMethod "$base/api/v1/vector-index/documents/$d" -Method Delete -TimeoutSec 60
        Say ("[DELETE-API] {0} code={1}" -f $d, $r.code)
    } catch { Say ("[DELETE-API-ERR] {0} {1}" -f $d, $_.Exception.Message) }
}

# 2) verify（DELETE API 后残留现状）
Say "[VERIFY-POST-DELETE-API]"
& pwsh -NoProfile -File (Join-Path $repo "docs\temp_docs\D39\d39_cleanup-verify.ps1") 2>&1 | ForEach-Object { Say "  $_" }

# 3) 若 MySQL 仍有夹具行（delete 端点对主表联动未验证→实测判定），执行 DB 级 apply 清理
$verifyTxt = Get-Content (Join-Path $out "cleanup-verify.txt") -Raw
if ($verifyTxt -match 'FIXTURE_ROWS_MYSQL_\w+=(?!0)\d+') {
    Say "[APPLY] MySQL/PG 夹具残留，执行 DB 级夹具 DELETE（仅限 6 个 DOC-D39-F*）"
    & pwsh -NoProfile -File (Join-Path $repo "docs\temp_docs\D39\d39_cleanup-verify.ps1") -Apply 2>&1 | ForEach-Object { Say "  $_" }
    Say "[VERIFY-FINAL]"
    & pwsh -NoProfile -File (Join-Path $repo "docs\temp_docs\D39\d39_cleanup-verify.ps1") 2>&1 | ForEach-Object { Say "  $_" }
}
Copy-Item (Join-Path $out "cleanup-verify.txt") (Join-Path $out "cleanup-verify-final.txt") -Force
[System.IO.File]::WriteAllLines((Join-Path $out "cleanup-api.log"), $log, $utf8)
Say "[DONE] 清理流程结束"
