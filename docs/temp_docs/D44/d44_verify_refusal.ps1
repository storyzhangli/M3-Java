# d44_verify_refusal.ps1 — C09 无证据拒答复验（任务书 H7 第4能力；D43 纪律继承）
# 版本注记：v2 口径修正——
#   REFUSE 合法路径含"检索有证据但不可回答→拒绝"（D34 ABONEARTAWE 语义），故断言=
#     decision=REFUSE ∧ sources=0 ∧ 答案无[S]标记 ∧ 拒绝文案（不伪造事实）；不强断 evidenceCount=0
#   R4 语料内对照题在本窗（enabled+矩阵夹具面）受 MXA 表格片段干扰 → 降级 OBSERVATION；
#     ANSWER 对照权威面=P2 C05 重跑（disabled 干净实例 + KP2 语料，20 题含 D37 拒答标签集）
#   模型故障注入面（FAILED 决策）：需 FAULT_INJECTION_ENABLED 专用实例，本窗 NOT RUN（重启预算纪律），
#     由 110/110 单测 FaultInjecting 系列覆盖，边界声明随 summary 落盘。
# 零模型调用：实例无 DASHSCOPE_API_KEY（KP1 冻结）→ REFUSE 于生成前短路（RagOrchestrator 决策分支），
#     mock 通道无外呼；生成模型级=NOT RUN 禁冒充。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'
$repo = 'E:\AI\project\AI-Learning-one\M3-Java'
$base = 'http://127.0.0.1:8080'
$ev = Join-Path $repo 'docs\evidence\D44\verify'
$prin = Get-Content (Join-Path $repo 'docs\temp_docs\D44\auth\principals.local.json') -Raw | ConvertFrom-Json
$tok = $prin.principals.userA.token
$h = @{ Authorization = "Bearer $tok"; 'Content-Type' = 'application/json; charset=utf-8' }
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Ask($q, $topK = 5) {
    $b = @{ question = $q; topK = $topK } | ConvertTo-Json -Compress
    (Invoke-RestMethod -Uri "$base/api/v1/rag/ask" -Method Post -Headers $h -Body $utf8.GetBytes($b) -TimeoutSec 60).data
}

$cases = @(
    @{ id = 'R1-offcorp-ev';   q = '电动汽车冬季续航里程会衰减多少？';            exp = 'REFUSE'; hard = $true },
    @{ id = 'R2-offcorp-trip'; q = '重庆周边有哪些适合亲子游的旅游景点？';        exp = 'REFUSE'; hard = $true },
    @{ id = 'R3-vague';        q = '怎么修？';                                    exp = 'CLARIFY,REFUSE'; hard = $true },
    @{ id = 'R4-onguard-OBS';  q = '主轴温度超过上限应该先检查哪些部位？';        exp = 'OBS'; hard = $false }
)

$results = New-Object System.Collections.ArrayList
$fail = 0
foreach ($c in $cases) {
    $d = Ask $c.q
    $dec = [string]$d.decision
    $srcN = @($d.sources).Count
    $ansTxt = (([string]$d.answer) -replace '\s+', ' ')
    $head = $ansTxt.Substring(0, [Math]::Min(80, $ansTxt.Length))
    if ($c.exp -eq 'OBS') {
        $v = 'OBSERVATION'
    } else {
        $expSet = $c.exp -split ','
        $hitDec = ($expSet -contains $dec)
        # 拒答安全面：无引用伪造（sources=0）+ 答案无 [S#] 标记 + 拒绝文案存在
        $noFake = ($srcN -eq 0 -and $ansTxt -notmatch '\[S\d')
        $hasText = ($ansTxt.Length -gt 0)
        $v = if ($hitDec -and $noFake -and $hasText) { 'PASS' } else { 'FAIL' }
    }
    if ($v -eq 'FAIL') { $fail++ }
    [void]$results.Add([pscustomobject]@{
        id = $c.id; q = $c.q; decision = $dec; sources = $srcN
        evidenceCount = [int]$d.evidenceCount; retrievedCount = [int]$d.retrievedCount
        retrievalStatus = [string]$d.retrievalStatus; generationStatus = [string]$d.generationStatus
        errorCode = [string]$d.errorCode; exp = $c.exp; verdict = $v; answerHead = $head
    })
    "{0,-18} decision={1,-8} src={2} ev={3} ret={4} {5}" -f $c.id, $dec, $srcN, $d.evidenceCount, $d.retrievedCount, $v
}
$summary = [pscustomobject]@{
    scope       = 'C09 无证据拒答（在线级探针，enabled 矩阵窗；权威基线面=P2 C05）'
    versionNote = 'v2：REFUSE 允许 evidenceCount>0（不可回答型拒绝合法，D34 语义）；R4 降级 OBS（本窗夹具面干扰）'
    cases       = $results
    fail        = $fail
    verdict     = $(if ($fail -eq 0) { 'ALL-PASS(3 hard cells)' } else { 'HAS-FAIL' })
    boundary    = [pscustomobject]@{
        modelLevelFaultInjection = 'NOT RUN（需 FAULT_INJECTION_ENABLED 专用实例；重启预算 2/6 纪律）；由 FaultInjecting 单测覆盖（110/110 聚合含）'
        realLlmChannel           = 'NOT RUN（实例无 DASHSCOPE_API_KEY，KP1 冻结）；REFUSE 为生成前短路，零模型调用断言=决策分支+无外呼配置实证'
        answerContrast           = 'ANSWER 对照移 P2：C05 重跑 disabled 干净实例 20 题（含 D37 拒答标签 17/10/2 分布对照）'
    }
}
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $ev 'refusal-summary.json') -Encoding UTF8
"SUMMARY-> $ev\refusal-summary.json verdict=$($summary.verdict)"
