# D33 最小 RAG 闭环 HTTP 演示脚本
# 用法：
#   1. 先以真实环境启动 D33（提供 PG_VECTOR_PASSWORD；真机模式再提供 AI_API_KEY 并设 AI_MODE=deepseek）
#   2. pwsh 执行本脚本：./D33-rag-demo.ps1
# 说明：本脚本不包含任何密钥/口令；仅向本地运行实例发起请求并打印统一响应。

param(
    [string]$BaseUrl = "http://localhost:8080/api/v1/rag"
)

function Invoke-Rag([string]$Name, [hashtable]$Body) {
    Write-Host "`n===== $Name =====" -ForegroundColor Cyan
    $json = $Body | ConvertTo-Json -Depth 5
    try {
        $resp = Invoke-RestMethod -Uri "$BaseUrl/ask" -Method Post `
            -ContentType "application/json; charset=utf-8" -Body $json
        $resp | ConvertTo-Json -Depth 8
    } catch {
        Write-Host "请求失败（HTTP 层）：$($_.Exception.Message)" -ForegroundColor Yellow
    }
}

# 场景1：文档可答（需库中已导入对应设备手册语料）
Invoke-Rag "场景1 文档可答" @{
    question = "水泵气蚀有哪些表现，如何处理？"
    topK     = 5
}

# 场景2：无关问题（观察是否受控说明证据不足，而非补造）
Invoke-Rag "场景2 无关问题" @{
    question = "今天天气适合出去玩吗？"
    topK     = 5
}

# 场景3：空检索（用不存在的 documentId 强制零命中；观察 generationStatus=SKIPPED_NO_EVIDENCE，且后端不调用 ChatModel）
Invoke-Rag "场景3 空检索（强制零命中）" @{
    question   = "任意问题"
    topK       = 5
    documentId = "d33-demo-not-exist-zzz"
}

# 场景4：模型超时无法通过 HTTP 稳定触发，已由受控测试 RagFourScenarioDemoTest 验证
# （generationStatus=FAILED_TIMEOUT，受控错误不伪装成功）。真机如需观察，可临时把模型读超时调到极小。
Write-Host "`n场景4 模型超时：见测试 RagFourScenarioDemoTest（受控 FAILED_TIMEOUT），HTTP 无法按需稳定触发。" -ForegroundColor Magenta
