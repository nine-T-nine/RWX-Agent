<#
  一条命令起两个参战会话：A1 先开局，检测到 match=1 后再起 A2。

      .\agent-tmp\team\start-team.ps1            # 正常跑
      .\agent-tmp\team\start-team.ps1 -DryRun    # 只打印将要做什么

  前提：游戏已经跑起来（agent-operation\tmp\port 存在）。
#>
param(
    [string]$Repo = 'X:\game\RWX',
    [int]$MatchTimeoutSeconds = 420,
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$team = $PSScriptRoot
Set-Location $Repo

$portFile = Join-Path $Repo 'agent-operation\tmp\port'
if (-not (Test-Path $portFile)) {
    throw "游戏还没起：先在另一个窗口跑  .\gradlew.bat :desktop:run '--args=--evaluate 2'"
}
$port = (Get-Content $portFile -Raw).Trim()
Write-Host "游戏端口 $port" -ForegroundColor DarkGray

Get-ChildItem $team -Filter 'log-*.ndjson' -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem $team -Filter 'err-*.log' -ErrorAction SilentlyContinue | Remove-Item -Force

function Start-Agent([string]$role) {
    $argList = @('-NoProfile', '-NoExit', '-File', "$team\agent.ps1", '-Role', $role, '-Repo', $Repo)
    if ($DryRun) { Write-Host ("would run: pwsh " + ($argList -join ' ')) -ForegroundColor Cyan; return }
    Start-Process pwsh -ArgumentList $argList -WindowStyle Minimized
}

Write-Host '[1/3] 起 A1（负责开局）' -ForegroundColor Green
Start-Agent 'a1'

Write-Host "[2/3] 等房间开起来（最多 $([int]($MatchTimeoutSeconds / 60)) 分钟）..." -ForegroundColor Green
$deadline = (Get-Date).AddSeconds($MatchTimeoutSeconds)
$started = $false
$commands = $null
while (-not $DryRun -and (Get-Date) -lt $deadline) {
    $commands = Get-ChildItem (Join-Path $Repo 'evaluate\output') -Filter 'commands-*.txt' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($commands -and (Select-String -LiteralPath $commands.FullName -Pattern 'match=1' -Quiet)) { $started = $true; break }
    Start-Sleep -Seconds 5
}
if ($started) { Write-Host "      对局已开始：$($commands.Name)" -ForegroundColor DarkGray }
else { Write-Warning '没等到 match=1：A1 可能卡住了，看 log-a1.ndjson / err-a1.log' }

Write-Host '[3/3] 起 A2（作战）' -ForegroundColor Green
Start-Agent 'a2'

Write-Host @"

已就绪。对局中随时看：
    .\agent-tmp\team\status-team.ps1
结束后收材料打分：
    .\agent-tmp\team\score.ps1 -Name '<对局名>' -Clean
停止会话：关掉那两个最小化的 PowerShell 窗口。
"@ -ForegroundColor Yellow
