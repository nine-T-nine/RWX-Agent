<#
  参战会话的启动器。一般由 start-team.ps1 调用，也可手动：
      pwsh -NoProfile -File agent-tmp\team\agent.ps1 -Role a1

  做的事：取密钥 → 设权限 → 拼提示词（prompt.txt + card-<role>.txt）→ 起无头会话。
  日志：log-<role>.ndjson（回合事件）、err-<role>.log（桥的握手与告警）。
#>
param(
    [Parameter(Mandatory)][ValidateSet('a1', 'a2', 'a3')][string]$Role,
    [string]$Repo = 'X:\game\RWX',
    [string]$Bridge = 'X:\game\rwx-dsh-bridge'
)
$ErrorActionPreference = 'Stop'
$team = $PSScriptRoot

$auth = Get-Content "$env:USERPROFILE\.local\share\opencode\auth.json" -Raw | ConvertFrom-Json
if (-not $auth.'opencode-go'.key) { throw '找不到 opencode-go 的密钥（auth.json）' }
$env:OPENCODE_API_KEY = $auth.'opencode-go'.key
$env:DSH_PERMISSION_MODE = 'danger-full-access'   # 无头没有审批通道

$prompt = (Get-Content "$Bridge\prompt.txt" -Raw) + "`n" + (Get-Content "$team\card-$Role.txt" -Raw)

Set-Location $Repo
$prompt | dsh --profile headless --patch "$team\provider.yml" --patch "$team\patch-$Role.yml" --json - `
    1> "$team\log-$Role.ndjson" 2> "$team\err-$Role.log"

Write-Host "[$Role] 会话结束；日志 $team\log-$Role.ndjson" -ForegroundColor Yellow
