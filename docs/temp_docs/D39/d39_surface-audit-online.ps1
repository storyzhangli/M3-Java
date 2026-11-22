# d39_surface-audit-online.ps1 — C18 暴露面收口在线取证（enabled=true 实例）
# 断言：USER 跨范围预览=4030；本人合规预览=200；ADMIN 租户内=200；无标签遗留=4030
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$tok = (Get-Content 'E:\AI\project\AI-Learning-one\M3-Java\docs\temp_docs\D39\auth\fixture-tokens.json' -Raw | ConvertFrom-Json).principals
$outFile = 'E:\AI\project\AI-Learning-one\M3-Java\docs\evidence\D39\enforcement\surface-audit-online.jsonl'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$lines = [System.Collections.Generic.List[string]]::new()
function Get-Code($path, $tn) {
    $h = @{}
    if ($tn) { $h['Authorization'] = "Bearer " + $tok.$tn.token }
    try { (Invoke-RestMethod ($base + $path) -Headers $h -TimeoutSec 30).code } catch { "HTTP_ERR" }
}
$cases = @(
    @{ p="/api/v1/documents/DOC-D39-F6/pages";        t="userA";   e=4030; d="userA 跨部门预览 F6 pages" }
    @{ p="/api/v1/documents/DOC-D39-F6/chunks";       t="userA";   e=4030; d="userA 跨部门预览 F6 chunks" }
    @{ p="/api/v1/documents/DOC-D39-F6/embeddings";   t="userA";   e=4030; d="userA 跨部门预览 F6 embeddings" }
    @{ p="/api/v1/vector-index/documents/DOC-D39-F6/chunks"; t="userA"; e=4030; d="userA 跨部门 vector-index chunks" }
    @{ p="/api/v1/vector-index/documents/DOC-D39-F6/status"; t="userA"; e=4030; d="userA 跨部门 vector-index status" }
    @{ p="/api/v1/documents/DOC-D39-F1/pages";        t="userA";   e=200;  d="userA 本人 F1 合规预览" }
    @{ p="/api/v1/documents/DOC-D39-F6/pages";        t="adminT1"; e=200;  d="adminT1 租户内 bypass" }
    @{ p="/api/v1/documents/DOC-D39-F5/pages";        t="adminT1"; e=4030; d="adminT1 跨租户 F5（H7）" }
    @{ p="/api/v1/documents/DOC-CNC-COOLING-001/pages"; t="userA"; e=4030; d="无标签遗留 fail-closed（D3）" }
)
foreach ($c in $cases) {
    $code = Get-Code $c.p $c.t
    $ok = ($code -eq $c.e)
    $lines.Add((@{ entry=$c.p; principal=$c.t; desc=$c.d; expected=$c.e; actual=$code; result=$(if ($ok) {"PASS"} else {"FAIL"}) } | ConvertTo-Json -Compress -Depth 4))
}
[System.IO.File]::WriteAllLines($outFile, $lines, $utf8)
$lines | ForEach-Object { $o = $_ | ConvertFrom-Json; "[{0}] {1} {2} exp={3} act={4}" -f $o.result, $o.principal, $o.entry, $o.expected, $o.actual }

