# D42 C01 步骤1：D41 测试迁移（d41→d42 全量 token 替换 + 目录落位）
# 来源：D41\D41_TASIIEM\src\test\java\com\zl\d41\{sync,table,table\fixtures,service}
# 目标：D42\D42_RERANK\src\test\java\com\zl\d42\ 同名子包
# 夹具：仅建目录（PDF/MD/truth/SHA 由 D42TableFixtureGenerator 再生，见步骤2）
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$src = 'E:\AI\project\AI-Learning-one\M3-Java\D41\D41_TASIIEM\src\test\java\com\zl\d41'
$dst = 'E:\AI\project\AI-Learning-one\M3-Java\D42\D42_RERANK\src\test\java\com\zl\d42'

$files = @(
    'sync\DocumentSyncServiceAtomicClaimTest.java',
    'sync\D41SyncControllerConsistencyTest.java',
    'sync\ReconciliationReportViewDriftSummaryTest.java',
    'sync\CitationDeletedClassificationTest.java',
    'sync\AdminAuditServiceTest.java',
    'sync\SyncOperationAndReasonCodeTest.java',
    'sync\FaultInjectingVectorStoreTest.java',
    'sync\SyncRetryRunnerPipelineTest.java',
    'sync\DocumentSyncServiceStateMachineTest.java',
    'service\RecoverStuckTasksScopeTest.java',
    'table\TableRecordRebuildTest.java',
    'table\TextTableExtractorTest.java',
    'table\TableCellTypingTest.java',
    'table\PdfTableFixtureTruthTest.java',
    'table\fixtures\D41TableFixtureGenerator.java'
)
$moved = 0
foreach ($f in $files) {
    $from = Join-Path $src $f
    $to   = Join-Path $dst ($f -creplace 'D41', 'D42')
    if (-not (Test-Path $from)) { Write-Host "MISS-SRC $f"; continue }
    New-Item -ItemType Directory -Force -Path (Split-Path $to) | Out-Null
    $text = Get-Content $from -Raw -Encoding UTF8
    # 注意：必须用 -creplace（大小写敏感）。-replace 不敏感会把 D41 类名在首轮小写
    # 规则中一起吃成 d42（d42SyncController 假类名），属本脚本首轮实测踩坑修正。
    $text = $text -creplace 'd41', 'd42' -creplace 'D41', 'D42'
    # 历史证据路径注释回正（D41 留痕不应指向 D42 目录）
    $text = $text -replace 'docs[/\\]evidence[/\\]D42', 'docs/evidence/D41'
    Set-Content -Path $to -Value $text -Encoding UTF8 -NoNewline
    $moved++
}
Write-Host "MIGRATED=$moved / $($files.Count)"
New-Item -ItemType Directory -Force -Path 'E:\AI\project\AI-Learning-one\M3-Java\D42\D42_RERANK\src\test\resources\d42-table-fixtures' | Out-Null
Write-Host 'FIXTURE-DIR ready'
