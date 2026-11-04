<#
  收材料 + 打分 + 清理，一条命令跑完。

      .\agent-tmp\team\score.ps1                                  # 用自动生成的对局名
      .\agent-tmp\team\score.ps1 -Name 'Ice-Island-(2p)-20261104--15-31_xxx'
      .\agent-tmp\team\score.ps1 -Name '...' -Clean               # 顺手删日志、重置信箱

  -Name 会先改目录名、回放名、目录内的文件名与文本引用，再评分。
#>
param(
    [string]$Name,
    [switch]$Clean,
    [string]$Repo = 'X:\game\RWX'
)
$ErrorActionPreference = 'Stop'
$team = $PSScriptRoot
Set-Location $Repo

function Rename-Match([string]$old, [string]$new) {
    $mOld = "evaluate/output/$old"
    $mNew = "evaluate/output/$new"
    $replayOld = "replays/$old.replay"
    if (Test-Path -LiteralPath $replayOld) { Rename-Item -LiteralPath $replayOld -NewName "$new.replay" }
    Rename-Item -LiteralPath $mOld -NewName $new
    Get-ChildItem -LiteralPath $mNew -File | Where-Object { $_.Name.Contains($old) } |
        ForEach-Object { Rename-Item -LiteralPath $_.FullName -NewName $_.Name.Replace($old, $new) }
    Get-ChildItem "$mNew\events-live" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name.Contains($old) } |
        ForEach-Object { Rename-Item -LiteralPath $_.FullName -NewName $_.Name.Replace($old, $new) }
    Get-ChildItem -LiteralPath $mNew -Recurse -File |
        Where-Object { $_.Extension -in '.json', '.txt', '.jsonl' } | ForEach-Object {
            $text = Get-Content -LiteralPath $_.FullName -Raw
            if ($text -and $text.Contains($old)) {
                Set-Content -LiteralPath $_.FullName -Value $text.Replace($old, $new) -Encoding utf8 -NoNewline
            }
        }
}

Write-Host '[1/5] 收敛材料' -ForegroundColor Green
$before = @(Get-ChildItem 'evaluate\output' -Directory | ForEach-Object { $_.Name })
& node evaluate/tools/match_materials.js --apply
if ($LASTEXITCODE -ne 0) { throw 'match_materials 失败' }
$created = @(Get-ChildItem 'evaluate\output' -Directory | Sort-Object LastWriteTime -Descending |
    ForEach-Object { $_.Name } | Where-Object { $_ -notin $before })
$auto = if ($created.Count -gt 0) { $created[0] } else { $null }
if (-not $auto -and -not $Name) { throw '没发现新的对局目录：先确认这一局有回放和 events-live' }
$match = if ($auto) { $auto } else { $Name }
if ($Name -and $auto -and $Name -ne $auto) {
    Write-Host "[2/5] 改名 $auto → $Name" -ForegroundColor Green
    Rename-Match $auto $Name
    $match = $Name
}
else { Write-Host "[2/5] 对局名 $match" -ForegroundColor Green }

$m = "evaluate/output/$match"
Write-Host '[3/5] 解析回放 + 预处理 + 两轮评分' -ForegroundColor Green
& A:\miniconda\python.exe evaluate/utils/replay.py "replays/$match.replay" --commands "$m/commands-$match.txt"
if ($LASTEXITCODE -ne 0) { throw 'replay.py 失败' }
& node evaluate/preprocess.js $m
if ($LASTEXITCODE -ne 0) { throw 'preprocess 失败' }
& node evaluate/evaluate_stage_1.js "$m/prepared/commands-$match.txt" "$m/prepared/replay.jsonl"
if ($LASTEXITCODE -ne 0) { throw 'stage_1 失败' }
& node evaluate/evaluate_stage_2.js "$m/prepared/telemetry.jsonl" --commands "$m/prepared/commands-$match.txt" `
    --economy "$m/prepared/economy.log" --replay "$m/prepared/replay.jsonl" --out "$m/stage_2"
if ($LASTEXITCODE -ne 0) { throw 'stage_2 失败' }
& node evaluate/score_stage_2.js "$m/stage_2"
if ($LASTEXITCODE -ne 0) { throw 'score_stage_2 失败' }
& node evaluate/score_match.js $m
if ($LASTEXITCODE -ne 0) { throw 'score_match 失败' }

Write-Host '[4/5] 刷新 meta' -ForegroundColor Green
& node evaluate/tools/match_materials.js --apply --match $m | Out-Null

Write-Host '[5/5] 结果' -ForegroundColor Green
$score = Get-Content "$m/score.json" -Raw | ConvertFrom-Json
Write-Host ("{0}`n  总分 {1}  = 第一轮 {2} × {3} + 第二轮 {4} × {5}" -f `
    $match, $score.total, $score.components.stage1.value, $score.weights.stage1,
    $score.components.stage2.value, $score.weights.stage2) -ForegroundColor Yellow

if ($Clean) {
    Get-ChildItem $team -Filter 'log-*.ndjson' -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem $team -Filter 'err-*.log' -ErrorAction SilentlyContinue | Remove-Item -Force
    Set-Content (Join-Path $team 'mail-a1.txt') '# A1 -> A2 信箱' -Encoding utf8
    Set-Content (Join-Path $team 'mail-a2.txt') '# A2 -> A1 信箱' -Encoding utf8
    Write-Host '已清理会话日志与信箱；记得更新 last-run.txt' -ForegroundColor DarkGray
}
