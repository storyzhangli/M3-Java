$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$sec = 'E:\AI\project\AI-Learning-one\M3-Java\docs\evidence\D39\security'
$enf = 'E:\AI\project\AI-Learning-one\M3-Java\docs\evidence\D39\enforcement'
New-Item -ItemType Directory -Force -Path $enf | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
function LoadJsonl($n) { Get-Content "$sec\$n.jsonl" | ForEach-Object { $_ | ConvertFrom-Json } }
$t1 = LoadJsonl 't1'; $t7 = LoadJsonl 't7'; $t8 = LoadJsonl 't8'; $t9 = LoadJsonl 't9'; $t10 = LoadJsonl 't10'

$pushV = [ordered]@{
    case      = "C07 KP4 向量路下推决定性证明"
    evidence  = "security/t1.jsonl + security/t9.jsonl + AccessPolicyServiceVectorPredicateTest"
    decisive  = @($t1 | Where-Object { $_.entry -eq "/api/v1/retrieval/search" })
    inDbProof = @($t9 | Where-Object { "$($_.entry)" -like "*vector-store/search*" })
    conclusion = "topK=20/threshold=0 下 F6(字面同锚点句)/F3 对 userA 零命中；userB 与 admin raw 通道同数据可见 => WHERE 下推先于 Top-K"
}
$pushK = [ordered]@{
    case       = "C08 关键词路同口径"
    evidence   = "security/t7.jsonl"
    decisive   = @($t7 | Where-Object { $_.query -eq "D2P-771" })
    conclusion = "精确码独占命中场景：userA 零命中、userB 必现 => 先过滤后排名，两路同谓词代数"
}
$fuse = [ordered]@{
    case       = "C09 融合闭包"
    evidence   = "security/t7.jsonl + RrfFusionClosureTest(单测)"
    closure    = @($t7 | Where-Object { $null -ne $_.fusionClosureOk } | Select-Object principal, entry, returnedChunkIds, fusionClosureOk, result)
    conclusion = "RRF 输出 ⊆ 两路已过滤候选并集（集成断言 fusionClosureOk=true + 纯函数单测）"
}
$ragE = [ordered]@{
    case       = "C10 RAG 入口许可集"
    evidence   = "security/t1.jsonl + t7.jsonl(rag cases) + t8.jsonl(无 token=4030)"
    rag        = @(@($t1 | Where-Object { "$($_.entry)" -like "*rag*" }) + @($t7 | Where-Object { "$($_.entry)" -like "*rag*" }))
    conclusion = "sources/citations ⊆ 许可集；无 AuthContext 拒绝(4030)"
}
$surf = [ordered]@{
    case       = "C18 暴露面收口"
    evidence   = "security/t8.jsonl(preview 无 token=4030; raw stats USER=4030/ADMIN=200) + t9.jsonl(admin raw 运维通道) + probe/endpoint-inventory.csv(KP1 分级)"
    rawAndPreview = @($t8 | Where-Object { "$($_.entry)" -like "*vector-store*" -or "$($_.entry)" -like "*pages*" })
    injection  = @($t10)
    conclusion = "预览/raw/rebuild 收口断言全绿；注入载荷下检索层零越权"
}
[System.IO.File]::WriteAllText("$enf\pushdown-vector.json",  ($pushV | ConvertTo-Json -Depth 8), $utf8)
[System.IO.File]::WriteAllText("$enf\pushdown-keyword.json", ($pushK | ConvertTo-Json -Depth 8), $utf8)
[System.IO.File]::WriteAllText("$enf\fusion-closure.json",   ($fuse | ConvertTo-Json -Depth 8), $utf8)
[System.IO.File]::WriteAllText("$enf\rag-entry.json",        ($ragE | ConvertTo-Json -Depth 8), $utf8)
[System.IO.File]::WriteAllText("$enf\surface-audit.json",    ($surf | ConvertTo-Json -Depth 8), $utf8)
Get-ChildItem $enf | ForEach-Object { "$($_.Name) $($_.Length)B" }
