# d39_fixture-access.ps1 — D39-R01 P4 权限夹具注入（6 文档 × 2 片段，任务书 §5）
# 语义：
#  - 三字段经 /documents/register 透传落 knowledge_document 三列（P2 链路），
#    /vector-index/index 入库时由 DocumentVectorIndexService 读三列注入 PG metadata 三键（P2）。
#  - 运行期 access.enabled=false（缺省）：写入链路不要求凭据；读侧权限在 T 矩阵阶段验证。
#  - 句式复用设备故障域，逐字不复制冻结语料（equipment-fault-corpus.json 只读，H6）。
#  - F6 含工单号 D2P-771 且片段0内嵌锚点问句原文（字面一致），作为权限过滤判别器：
#    未过滤时其相似度压过冻结 top1；正确过滤后 userA（T1/D1/DEV-A）不可见 F6（dept D2）。
# 幂等：register 重复 4001 容忍；index 天然幂等（同 payloadHash 短路）。
# 证据：docs\evidence\D39\fixture\
param([string]$RepoRoot = "E:\AI\project\AI-Learning-one\M3-Java")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$base = "http://127.0.0.1:8080"
$evDir = Join-Path $RepoRoot "docs\evidence\D39\fixture"
New-Item -ItemType Directory -Force -Path $evDir | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

# 权限矩阵（冻结口径：F1..F6）
$fixtures = @(
    @{ documentId="DOC-D39-F1"; title="主轴过热排查·部门一设备A（权限夹具）"; tenantId="T1"; deptId="D1"; deviceId="DEV-A";
       chunks=@(
        "数控机床报主轴过热告警时，先核实冷却回路是否循环正常，再确认主轴油膜间隙是否在合格范围，逐项打勾记录排查数值。",
        "DEV-A 设备在夏季环境频繁报主轴高温时，应检查冷机制冷剂压力，低于0.6兆帕需补充制冷剂并复测温升曲线。") }
    @{ documentId="DOC-D39-F2"; title="部门一通用设备故障常识（权限夹具）"; tenantId="T1"; deptId="D1"; deviceId="*";
       chunks=@(
        "液压系统压力下降多与溢流阀设定压力和内泄有关，先用标准压力表核对系统实际压力，再拆检溢流阀。",
        "更换滚珠丝杠润滑脂前必须记录轴定位误差基准，换脂后往复运行三段行程使油脂分布均匀再复测。") }
    @{ documentId="DOC-D39-F3"; title="部门二设备B维护卡（权限夹具）"; tenantId="T1"; deptId="D2"; deviceId="DEV-B";
       chunks=@(
        "DEV-B 输送线变频过流报警常见于制动电阻老化，断电后用兆欧表检查制动电阻阻值与接线端子绝缘。",
        "D2 部门巡检机器人每日八点上线，故障工单自动归档至 D2 台账并联动备件库存扣减。") }
    @{ documentId="DOC-D39-F4"; title="不限部门设备通识（权限夹具）"; tenantId="T1"; deptId="*"; deviceId="*";
       chunks=@(
        "任何设备动火作业前必须完成上锁挂牌，经值班主管确认签字后方可办理作业票。",
        "万用表测量电压前先选择交直流档位与量程，测量高压前在已知带电体验表确认仪表正常。") }
    @{ documentId="DOC-D39-F5"; title="租户二夹具知识（权限夹具）"; tenantId="T2"; deptId="D1"; deviceId="*";
       chunks=@(
        "租户 T2 的告警码表与 T1 不同：T2 的 E12 表示润滑超时而非过压，跨租户引用码表会误判。",
        "T2 台账规范要求工单必须携带租户标识，禁止跨租户引用设备与备件数据。") }
    @{ documentId="DOC-D39-F6"; title="部门二工单档案（权限夹具 D2P-771）"; tenantId="T1"; deptId="D2"; deviceId="*";
       chunks=@(
        "工单 D2P-771：数控机床出现主轴过热报警，应该先检查什么？现场回复建议先查冷却系统。本片段字面与提问一致，用于权限判别。",
        "D2P-771 回访记录：更换冷却泵叶轮后主轴温升回到正常区间，复测告警消除，工单按规范关闭。") }
)

# 1) 登记（带三字段；重复 4001 容忍）
foreach ($f in $fixtures) {
    $reg = @{ source="fixture-access"; title=$f.title; documentId=$f.documentId;
              tenantId=$f.tenantId; deptId=$f.deptId; deviceId=$f.deviceId } | ConvertTo-Json
    $resp = Invoke-RestMethod -Uri "$base/api/v1/documents/register" -Method Post `
        -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($reg)) -TimeoutSec 30
    Write-Output ("[REGISTER] " + $f.documentId + " code=" + $resp.code + " msg=" + $resp.msg)
    if ($resp.code -notin @(200, 4001)) { throw ("登记失败: " + $f.documentId) }
}

# 2) 入库（STRUCTURE v1，逐文档 2 片段）
$results = @()
foreach ($f in $fixtures) {
    $payloadChunks = @(); $offset = 0
    for ($i = 0; $i -lt $f.chunks.Count; $i++) {
        $c = $f.chunks[$i]
        $payloadChunks += @{ chunkIndex=$i; content=$c; globalStart=$offset; globalEnd=$offset+$c.Length }
        $offset += $c.Length
    }
    $req = @{ documentId=$f.documentId; version=1; strategy="STRUCTURE"; title=$f.title; chunks=$payloadChunks } | ConvertTo-Json -Depth 6
    $resp = Invoke-RestMethod -Uri "$base/api/v1/vector-index/index" -Method Post `
        -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($req)) -TimeoutSec 120
    Write-Output ("[INDEX] " + $f.documentId + " code=" + $resp.code + " status=" + $resp.data.status + " chunks=" + $resp.data.chunkCount)
    if ($resp.code -ne 200) { throw ("入库失败: " + $f.documentId) }
    $results += @{ documentId=$f.documentId; resp=$resp.data }
}
[System.IO.File]::WriteAllText((Join-Path $evDir "fixture-results.json"), ($results | ConvertTo-Json -Depth 8), $utf8)

# 3) 核验：每文档 chunks=2 + 返回 chunkId 集合留痕（T 矩阵断言基线）
$verify = [ordered]@{}
foreach ($f in $fixtures) {
    $ch = Invoke-RestMethod -Uri "$base/api/v1/vector-index/documents/$($f.documentId)/chunks" -TimeoutSec 15
    $ids = @($ch.data | ForEach-Object { $_.chunkId })
    Write-Output ("[VERIFY] " + $f.documentId + " chunks=" + $ids.Count)
    if ($ids.Count -ne 2) { throw ("夹具片段数异常: " + $f.documentId) }
    $verify[$f.documentId] = @{ tenantId=$f.tenantId; deptId=$f.deptId; deviceId=$f.deviceId; chunkIds=$ids }
}
[System.IO.File]::WriteAllText((Join-Path $evDir "fixture-manifest.json"), ($verify | ConvertTo-Json -Depth 6), $utf8)
Write-Output "[DONE] D39 权限夹具注入完成（6 文档 12 片段），manifest 已落盘"
