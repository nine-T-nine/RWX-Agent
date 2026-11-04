<#
.SYNOPSIS
    RWX CLI 常驻客户端：一条 TCP 连接一直挂着，命令通过文件收发，避免每条命令重启一次进程。

.DESCRIPTION
    游戏里的 CLI 端点是“一条连接可以连续发很多命令”的，但普通调用方式每条命令都要启动一次
    PowerShell（约 1~2 秒），实时对局里这个开销很碍事。本脚本把连接放在一个后台进程里：

        tmp\rwx-cli\req.txt    外部写入一条命令（覆盖写）
        tmp\rwx-cli\resp.txt   daemon 写回这条命令的回复
        tmp\rwx-cli\state.txt  人类可读的状态（端口、pid、最后一条命令、时间）
        tmp\rwx-cli\pid.txt    daemon 的进程号
        tmp\rwx-cli\daemon.log 连接/重连/错误日志

    判断“这条回复是不是我这次请求的”：写请求后比较两个文件的修改时间——
    resp.txt 比 req.txt 新，就说明 daemon 已经处理完。
    `tools\rwx-cli.sh` 封装了这套等待逻辑，bash 里直接 `./rwx-cli.sh send "rwx-game-brief"`。

    连接断了（游戏重开、游戏退出）会自动重连；重连期间收到的请求会立刻回一条 error，
    不会让调用方一直等。连续 -IdleMinutes 分钟没有请求就自己退出。

.PARAMETER Start
    启动后台 daemon 并等到它连上（或超时报错）。

.PARAMETER Stop
    停止后台 daemon。

.PARAMETER Status
    打印当前状态与最后一次回复。

.PARAMETER Run
    前台运行 daemon（-Start 内部就是这样拉起自己的；一般不用手写）。

.PARAMETER Dir
    收发目录，默认 .\tmp\rwx-cli。

.PARAMETER Port / Id / ProcessName / Log
    端点发现参数，和 rwx-cli.ps1 一致；都不给时自动发现。

.PARAMETER TimeoutMs
    单条命令的等待上限，默认 600000（10 分钟，够 step 跑一段）。

.PARAMETER IdleMinutes
    无请求多久后退出，默认 180。

.PARAMETER PollMs
    轮询 req.txt 的间隔，默认 25。

.EXAMPLE
    .\rwx-cli-daemon.ps1 -Start

.EXAMPLE
    .\rwx-cli-daemon.ps1 -Status

.EXAMPLE
    .\rwx-cli-daemon.ps1 -Stop

.NOTES
    退出码：0 成功；2 参数/状态问题；3 连不上端点。
#>
[CmdletBinding()]
param(
    [switch]$Start,
    [switch]$Stop,
    [switch]$Status,
    [switch]$Run,
    [string]$Dir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp/daemon'),
    [int]$Port = 0,
    [int]$Id = 0,
    [string]$ProcessName,
    [string]$Log,
    [int]$TimeoutMs = 600000,
    [int]$IdleMinutes = 180,
    [int]$PollMs = 25
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rwx-cli-lib.ps1')

function Resolve-RwxDir {
    param([string]$Path)
    if (-not (Test-Path $Path)) { New-Item -ItemType Directory -Path $Path -Force | Out-Null }
    return (Resolve-Path $Path).Path
}

$directory = Resolve-RwxDir -Path $Dir
$reqPath = Join-Path $directory 'req.txt'
$respPath = Join-Path $directory 'resp.txt'
$statePath = Join-Path $directory 'state.txt'
$pidPath = Join-Path $directory 'pid.txt'
$logPath = Join-Path $directory 'daemon.log'

function Write-RwxState {
    param([string]$Phase, [int]$PortNumber = 0, [string]$LastCommand = '')
    $lines = @(
        "phase=$Phase",
        "pid=$PID",
        "port=$PortNumber",
        "updated=$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))",
        "lastCommand=$LastCommand",
        "dir=$directory",
        "timeoutMs=$TimeoutMs"
    )
    $tmp = "$statePath.tmp"
    [System.IO.File]::WriteAllText($tmp, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -Force $tmp $statePath
}

function Write-RwxLog {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date).ToString('HH:mm:ss'), $Message
    Add-Content -Path $logPath -Value $line -Encoding UTF8
}

function Test-RwxDaemonAlive {
    if (-not (Test-Path $pidPath)) { return $false }
    $raw = (Get-Content -Path $pidPath -Raw -ErrorAction SilentlyContinue)
    if (-not $raw) { return $false }
    $processId = 0
    if (-not [int]::TryParse($raw.Trim(), [ref]$processId)) { return $false }
    return $null -ne (Get-Process -Id $processId -ErrorAction SilentlyContinue)
}

if ($Status) {
    if (-not (Test-Path $statePath)) {
        Write-Output "状态: 没有运行（$directory 下没有 state.txt）"
        exit 2
    }
    Get-Content -Path $statePath -Encoding UTF8 | ForEach-Object { Write-Output $_ }
    if (Test-Path $respPath) {
        Write-Output '--- 最后一次回复 ---'
        Get-Content -Path $respPath -Encoding UTF8 | ForEach-Object { Write-Output $_ }
    }
    if (Test-RwxDaemonAlive) { exit 0 } else { Write-Output '（进程已不存在）'; exit 2 }
}

if ($Stop) {
    if (-not (Test-RwxDaemonAlive)) {
        Write-Output '没有正在运行的 daemon。'
        if (Test-Path $pidPath) { Remove-Item $pidPath -Force -ErrorAction SilentlyContinue }
        exit 0
    }
    $processId = [int]((Get-Content -Path $pidPath -Raw).Trim())
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 200
    Remove-Item $pidPath -Force -ErrorAction SilentlyContinue
    Write-RwxLog 'manual stop'
    Write-Output "已停止 daemon（pid $processId）。"
    exit 0
}

if ($Start) {
    if (Test-RwxDaemonAlive) {
        Write-Output 'daemon 已经在运行（先 -Stop 再 -Start 可以换目录/端点）。'
        exit 0
    }
    # 旧回复会让调用方误以为是这次的答案。
    Remove-Item $respPath -Force -ErrorAction SilentlyContinue
    $arguments = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-Run',
        '-Dir', $directory, '-TimeoutMs', $TimeoutMs, '-IdleMinutes', $IdleMinutes, '-PollMs', $PollMs
    )
    if ($Port -gt 0) { $arguments += @('-Port', $Port) }
    if ($Id -gt 0) { $arguments += @('-Id', $Id) }
    if ($ProcessName) { $arguments += @('-ProcessName', $ProcessName) }
    if ($Log) { $arguments += @('-Log', $Log) }
    $process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments `
        -WorkingDirectory (Get-Location).Path -WindowStyle Hidden -PassThru
    $process.Id | Set-Content -Path $pidPath -Encoding ASCII
    # 等它连上；连不上也要把原因说清楚，而不是只报“启动超时”。
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $statePath) {
            $state = Get-Content -Path $statePath -Raw -Encoding UTF8
            if ($state -match 'phase=running') {
                $portMatch = [regex]::Match($state, 'port=(\d+)')
                $shown = if ($portMatch.Success) { $portMatch.Groups[1].Value } else { '?' }
                Write-Output "daemon 已启动（pid $($process.Id)，port $shown）。"
                Write-Output "收发目录: $directory"
                exit 0
            }
        }
        Start-Sleep -Milliseconds 200
    }
    [Console]::Error.WriteLine('失败: daemon 20 秒内没有连上端点。看 daemon.log，或先用 rwx-cli.ps1 -List 确认端点。')
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Remove-Item $pidPath -Force -ErrorAction SilentlyContinue
    exit 3
}

if (-not $Run) {
    [Console]::Error.WriteLine('失败: 需要 -Start / -Stop / -Status / -Run 之一。')
    exit 2
}

# ---------------- 前台 daemon 循环 ----------------
$PID | Set-Content -Path $pidPath -Encoding ASCII
Write-RwxLog "daemon start (pid=$PID, dir=$directory)"
Write-RwxState -Phase 'connecting'

$session = $null
$sessionPort = 0
$lastRequestStamp = $null
$lastCommand = ''
$lastConnectAttempt = [datetime]::MinValue
$lastStateWrite = [datetime]::MinValue
$lastActivity = Get-Date

try {
    while ($true) {
        $now = Get-Date
        if ($null -eq $session) {
            if (($now - $lastConnectAttempt).TotalSeconds -ge 2.0) {
                $lastConnectAttempt = $now
                $endpoint = Resolve-RwxEndpoint -Port $Port -Id $Id -ProcessName $ProcessName -Log $Log
                if ($null -ne $endpoint) {
                    try {
                        $session = Connect-RwxFramed -Port $endpoint.Port -TimeoutMs $TimeoutMs
                        $sessionPort = $endpoint.Port
                        Write-RwxLog "connected 127.0.0.1:$sessionPort ($($endpoint.Source))"
                        Write-RwxState -Phase 'running' -PortNumber $sessionPort -LastCommand $lastCommand
                    } catch {
                        Write-RwxLog "connect failed: $($_.Exception.Message)"
                        $session = $null
                        Write-RwxState -Phase 'connecting'
                    }
                } else {
                    Write-RwxLog 'endpoint not found'
                    Write-RwxState -Phase 'waiting-for-game'
                }
            }
        }

        if (Test-Path $reqPath) {
            $stamp = (Get-Item $reqPath).LastWriteTimeUtc.Ticks
            if ($stamp -ne $lastRequestStamp) {
                $lastRequestStamp = $stamp
                $raw = (Get-Content -Path $reqPath -Raw -ErrorAction SilentlyContinue)
                $line = if ($null -eq $raw) { '' } else { $raw.Trim() }
                if ($line) {
                    $lastCommand = $line
                    $lastActivity = Get-Date
                    $reply = $null
                    if ($null -eq $session) {
                        $reply = 'error: 还没有连上游戏 CLI 端点（游戏要用 --rwx-cli 启动；'
                        $reply += '先 rwx-cli.ps1 -List 确认，或重新启动 daemon）'
                    } else {
                        try {
                            $argv = ConvertFrom-RwxCommandLine -Line $line
                            $rest = @()
                            if ($argv.Count -gt 1) { $rest = @($argv[1..($argv.Count - 1)]) }
                            $reply = Invoke-RwxFramedCommand -Session $session -Command $argv[0] `
                                -Arguments $rest -TimeoutMs $TimeoutMs
                        } catch {
                            $reply = "error: $($_.Exception.Message)"
                            if ($_.Exception -is [System.IO.IOException] -or
                                $_.Exception -is [System.ObjectDisposedException] -or
                                $_.Exception -is [System.InvalidOperationException]) {
                                Write-RwxLog "connection dropped: $($_.Exception.Message)"
                                Close-RwxFramed -Session $session
                                $session = $null
                                Write-RwxState -Phase 'reconnecting' -PortNumber $sessionPort
                            }
                        }
                    }
                    $tmp = "$respPath.tmp"
                    [System.IO.File]::WriteAllText($tmp, ($reply + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
                    Move-Item -Force $tmp $respPath
                    Write-RwxLog ("reply: " + $line)
                }
            }
        }

        if (($now - $lastStateWrite).TotalSeconds -ge 10.0) {
            $lastStateWrite = $now
            if ($null -ne $session) {
                Write-RwxState -Phase 'running' -PortNumber $sessionPort -LastCommand $lastCommand
            }
        }

        if (((Get-Date) - $lastActivity).TotalMinutes -ge $IdleMinutes) {
            Write-RwxLog 'idle timeout'
            break
        }
        Start-Sleep -Milliseconds $PollMs
    }
} finally {
    Close-RwxFramed -Session $session
    Write-RwxState -Phase 'stopped'
    Remove-Item $pidPath -Force -ErrorAction SilentlyContinue
    Write-RwxLog 'daemon exit'
}
