# D41-R03 225 真值格复算工具（R02/R03 对账口径唯一权威，先于整改实现编写）
# 口径来源：docs\evidence\D41\R01\cell-diff.json meta.diffMethod 原文：
#   "行标签前缀匹配抽取行；真值与抽取值字符串等值比对；列可辨识=模型名列名（原文含□）
#    出现在该页抽取表头。value_hit=行内值命中；missing_in_extraction=行或值缺失；
#    wrong_value=行内出现与任何真值不符的错值"
# 规则实现（R01 样本反推自洽，须能在整改前数据复现 27/67/131/0 才算口径等价）：
#   行匹配：抽取行任一非空 cell == 行标签 或 StartsWith(行标签)（兼容 E4 黏连串）
#   列可辨识：真值列名 IndexOf 抽取 header 表头数组
#   值命中：expect 与行内任一非空 cell value 字符串全等
#   class：行缺失/值缺失 → missing(got=行格拼接)；值命中且列可辨识 → value_hit；
#          值命中但列不可辨识 → value_hit_column_unaligned
#   wrong_value：命中行内非空、非行标签、非数值/型号形态与真值不符的格 → 计数
param(
  [Parameter(Mandatory=$true)][string]$Extracted,
  [Parameter(Mandatory=$true)][string]$Truth,
  [Parameter(Mandatory=$true)][string]$Out
)
$ErrorActionPreference='Stop'
$ext = (Get-Content $Extracted -Raw | ConvertFrom-Json).tables
$truthDoc = Get-Content $Truth -Raw | ConvertFrom-Json
"DEBUG ext=$($ext.Count) truthTables=$($truthDoc.tables.Count)"

$byPage = @{}
foreach($t in $ext){
  if(-not $byPage.ContainsKey($t.pageNumber)){ $byPage[$t.pageNumber]=@() }
  $byPage[$t.pageNumber] += ,$t
}

$cells = New-Object System.Collections.Generic.List[object]
$stat = @{ truth_cells=0; value_hit=0; value_hit_column_unaligned=0; missing_in_extraction=0; wrong_value=0 }

foreach($tb in $truthDoc.tables){
  # hashtable 键统一 [long]（ConvertFrom-Json 整数为 Int64，[int] 键 ContainsKey 恒 False——本次校准实测踩坑）
  $page = [long]$tb.pdfPage
  $tables = if($byPage.ContainsKey($page)){ $byPage[$page] } else { @() }
  # 页内主表：行数最大者（并列取表头含真值模型列最多者）
  $main = $null
  foreach($cand in $tables){
    $score = @($cand.header | Where-Object { $tb.header -contains $_ }).Count
    if($null -eq $main -or ($cand.rows.Count -gt $main.rows.Count) -or
       ($cand.rows.Count -eq $main.rows.Count -and $score -gt @($main.header | Where-Object { $tb.header -contains $_ }).Count)) {
      $main = $cand
    }
  }
  $pageTruthValues = @{}
  foreach($r in $tb.rows){ foreach($v in $r[1..($r.Count-1)]){ $pageTruthValues[[string]$v]=1 } }

  foreach($r in $tb.rows){
    $rowLabel = [string]$r[0]
    # 行匹配=R01 原口径复现（校准实证）：收集所有"某格值 StartsWith(行标签)"的行，
    # 值池为多行并集——W 命中 WK+W 两行（R01 missing 格 got=",,WK,,5,,,W,,-" 双行拼接为铁证）
    $matchedRows = @()
    if($null -ne $main){
      foreach($row in $main.rows){
        $hitRow=$false
        foreach($cell in $row){
          $v=[string]$cell.value
          if($v -and $v.StartsWith($rowLabel)){ $hitRow=$true; break }
        }
        if($hitRow){ $matchedRows += ,$row }
      }
    }
    $matchedRow = if($matchedRows.Count){ $matchedRows[0] } else { $null }
    $vals = @($matchedRows | ForEach-Object { $_ } | ForEach-Object { [string]$_.value } | Where-Object { $_ })
    $gotStr = if($matchedRows.Count){ (($matchedRows | ForEach-Object { $row=$_; ($row | ForEach-Object { if($_.value){[string]$_.value}else{''} }) -join ',' }) -join ',') } else { 'ROW_ABSENT' }

    for($c=1; $c -lt $tb.header.Count; $c++){
      $col = [string]$tb.header[$c]
      $expect = [string]$r[$c]
      $stat.truth_cells++
      $colIdx = if($null -ne $main){ [Array]::IndexOf($main.header, $col) } else { -1 }
      $hit = ($vals -contains $expect)
      if($null -eq $matchedRow -or -not $hit){
        $cls='missing_in_extraction'; $stat.missing_in_extraction++
      } elseif($colIdx -ge 0){
        $cls='value_hit'; $stat.value_hit++
      } else {
        $cls='value_hit_column_unaligned'; $stat.value_hit_column_unaligned++
      }
      $cells.Add([pscustomobject]@{ page=$page; row=$rowLabel; col=$col; expect=$expect; got=$(if($cls -eq 'missing_in_extraction'){$gotStr}else{'VALUE_PRESENT'}); class=$cls })
    }
    # wrong_value：命中行内出现的、与本页任何真值/行标签不符的数值形态格
    foreach($mr in $matchedRows){
      foreach($cell in $mr){
        $v=[string]$cell.value
        if(-not $v){ continue }
        if($v -eq $rowLabel -or $v.StartsWith($rowLabel)){ continue }
        if($pageTruthValues.ContainsKey($v)){ continue }
        # 仅统计数值形态（含 E4 黏连串中的数值段拆分后），文本/表头碎片不计（与 R01 口径一致：本手册 0）
        if($v -match '^[-+±]?[0-9][0-9,\.]*$'){ $stat.wrong_value++ }
      }
    }
  }
}

$outObj = [pscustomobject]@{
  stat = $stat
  cells = $cells
  meta = [pscustomobject]@{
    producedBy = "d41_r03_cell_diff.ps1（R01 口径复现工具；对账规则见脚本头注释）"
    extractedFile = (Split-Path $Extracted -Leaf)
    rule = '行标签前缀匹配；值字符串等值；列可辨识=列名∈抽取表头；ROW_ABSENT=行未落库'
  }
}
$outObj | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 $Out
"STAT: " + ($stat | ConvertTo-Json -Compress)
