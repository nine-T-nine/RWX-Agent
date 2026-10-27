<#
.SYNOPSIS
    在游戏外面给 RWX 发送 CLI 命令（不需要、也不依赖游戏启动时弹出的那个命令终端）。

.DESCRIPTION
    游戏用 --rwx-cli 启动时，会在自己进程里开一个 127.0.0.1 的回环 TCP 端点；
    “RWX Command Terminal” 只是这个端点的一个 PowerShell 客户端。任何进程都能连上去发命令，
    协议是：一行 `<命令名><TAB><base64(argv1)>,<base64(argv2)>,...`。

    本脚本就是这样一个客户端，并负责找端口：
      1. -Port 直接给；
      2. -Id / -ProcessName：枚举该进程在回环地址上的 LISTENING 端口；
      3. 默认先读取 RWX_CLI_PORT_FILE 或 agent-operation/tmp/port，并用 rwx-ping 验证；
      4. -Log：从游戏 stdout 日志里找最后一行 `RWX_CLI_TOOL_READY port=<n>`。

    三种用法：
      * 单条：  .\rwx-cli.ps1 rwx-game-state --summary
      * 批量：  .\rwx-cli.ps1 -BatchFile .\commands.txt   （一条连接跑完，省掉每条一次的进程启动）
      * 交互：  .\rwx-cli.ps1 -Interactive              （REPL，一条连接连续发）
    批量/交互走 framed 协议：回复用 RS(0x1E) 包起来，多行输出也能准确分隔。

.PARAMETER Command
    命令名，例如 rwx-help、rwx-game-state、rwx-game-order-move。

.PARAMETER Arguments
    命令参数，原样跟在后面即可（不要加引号也可以，PowerShell 会把它们当一个数组传进来）。

.PARAMETER Port
    直接指定端口（跳过发现流程）。

.PARAMETER Id
    游戏进程 PID。

.PARAMETER ProcessName
    进程名，默认依次尝试 rwx、RWX、java（java 只认窗口标题里带 RWX 的进程）。

.PARAMETER Log
    游戏 stdout 日志路径，默认 .\rwx-agent-run.log（存在才用）。

.PARAMETER TimeoutMs
    等待回执的毫秒数，默认 60000；不等待长命令（如 step）时可调大。

.PARAMETER List
    只输出发现到的端点，不发命令。

.PARAMETER Batch
    一组命令行字符串，例如 -Batch "rwx-game-state","rwx-game-pause --value true"。

.PARAMETER BatchFile
    批量命令文件：每行一条完整命令，空行与 # 开头的行忽略。

.PARAMETER Interactive
    交互模式：读一行发一条，exit / quit 退出。

.EXAMPLE
    .\rwx-cli.ps1 -List

.EXAMPLE
    .\rwx-cli.ps1 rwx-game-state --summary

.EXAMPLE
    .\rwx-cli.ps1 -Id 40228 rwx-game-order-move --units group:main --x 1400 --y 700

.EXAMPLE
    .\rwx-cli.ps1 -Batch "rwx-game-state --summary","rwx-game-unit-list --owner self --compact"

.NOTES
    退出码：0 成功；2 找不到可用的 CLI 端点或参数错误；3 连接失败；4 命令返回以 error: 开头。
    常驻连接可用 -Interactive / -BatchFile。
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Command,
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Arguments = @(),
    [int]$Port = 0,
    [int]$Id = 0,
    [string]$ProcessName,
    [string]$Log,
    [int]$TimeoutMs = 60000,
    [switch]$List,
    [string[]]$Batch,
    [string]$BatchFile,
    [switch]$Interactive
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'rwx-cli-lib.ps1')

$endpoint = Resolve-RwxEndpoint -Port $Port -Id $Id -ProcessName $ProcessName -Log $Log

if ($List) {
    if (-not $endpoint) {
        Write-Output '没有发现可用的 RWX CLI 端点（游戏需要用 --rwx-cli 启动；也可以直接给 -Port）。'
        exit 2
    }
    Write-Output ("port: {0}  ({1})" -f $endpoint.Port, $endpoint.Source)
    exit 0
}

if (-not $endpoint) {
    [Console]::Error.WriteLine('失败: 没有发现可用的 RWX CLI 端点。游戏需要用 --rwx-cli 启动，或用 -Port/-Id 指定。')
    exit 2
}

# 把一行文本拆成 命令 + 参数；解析失败时返回 $null（批量模式继续跑下一条）。
function Split-RwxLine {
    param([string]$Line)
    $argv = ConvertFrom-RwxCommandLine -Line $Line
    $rest = @()
    if ($argv.Count -gt 1) { $rest = @($argv[1..($argv.Count - 1)]) }
    return [pscustomobject]@{ Command = $argv[0]; Arguments = $rest }
}

if ($BatchFile -or $Batch) {
    $lines = New-Object System.Collections.Generic.List[string]
    if ($BatchFile) {
        if (-not (Test-Path $BatchFile)) {
            [Console]::Error.WriteLine("失败: 找不到批量文件 $BatchFile")
            exit 2
        }
        foreach ($line in (Get-Content -Path $BatchFile -Encoding UTF8)) {
            $trimmed = $line.Trim()
            if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
            $lines.Add($trimmed)
        }
    }
    if ($Batch) { foreach ($line in $Batch) { if ($line -and $line.Trim()) { $lines.Add($line.Trim()) } } }
    if ($lines.Count -eq 0) {
        [Console]::Error.WriteLine('失败: 批量模式没有可执行的命令行。')
        exit 2
    }

    $session = $null
    try {
        $session = Connect-RwxFramed -Port $endpoint.Port -TimeoutMs $TimeoutMs
    } catch {
        [Console]::Error.WriteLine("失败: 连接 127.0.0.1:$($endpoint.Port) 出错：$($_.Exception.Message)")
        exit 3
    }
    $anyError = $false
    $sent = 0
    foreach ($line in $lines) {
        Write-Output ("### " + $line)
        try {
            $parsed = Split-RwxLine -Line $line
            $reply = Invoke-RwxFramedCommand -Session $session -Command $parsed.Command `
                -Arguments $parsed.Arguments -TimeoutMs $TimeoutMs
        } catch {
            $reply = "error: $($_.Exception.Message)"
        }
        Write-Output $reply
        if ($reply.StartsWith('error:')) { $anyError = $true }
        $sent++
    }
    Close-RwxFramed -Session $session
    Write-Output ("### done: {0}/{1}" -f $sent, $lines.Count)
    if ($anyError) { exit 4 }
    exit 0
}

if ($Interactive) {
    try {
        $session = Connect-RwxFramed -Port $endpoint.Port -TimeoutMs $TimeoutMs
    } catch {
        [Console]::Error.WriteLine("失败: 连接 127.0.0.1:$($endpoint.Port) 出错：$($_.Exception.Message)")
        exit 3
    }
    Write-Host ("连上 127.0.0.1:{0}。输入命令，exit 退出。" -f $endpoint.Port)
    while ($true) {
        $line = Read-Host 'rwx'
        if ($null -eq $line) { break }
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed -in @('exit', 'quit', ':q')) { break }
        try {
            $parsed = Split-RwxLine -Line $trimmed
            Write-Output (Invoke-RwxFramedCommand -Session $session -Command $parsed.Command `
                -Arguments $parsed.Arguments -TimeoutMs $TimeoutMs)
        } catch {
            [Console]::Error.WriteLine("失败: $($_.Exception.Message)")
        }
    }
    Close-RwxFramed -Session $session
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Command)) {
    [Console]::Error.WriteLine('失败: 缺少命令名，例如 rwx-game-state（可先用 rwx-help 看全部命令）。')
    exit 2
}

$encoded = @($Arguments | ForEach-Object {
    if ($null -eq $_) { '' } else {
        [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes([string]$_))
    }
}) -join ','

try {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', $endpoint.Port)
    $client.ReceiveTimeout = $TimeoutMs
    $stream = $client.GetStream()
    $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
    $writer.AutoFlush = $true
    $writer.WriteLine(("{0}`t{1}" -f $Command, $encoded))
    $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    $reply = $reader.ReadToEnd().TrimEnd("`r", "`n")
    $client.Dispose()
} catch {
    [Console]::Error.WriteLine("失败: 连接 127.0.0.1:$($endpoint.Port) 出错：$($_.Exception.Message)")
    exit 3
}

if ([string]::IsNullOrEmpty($reply)) {
    [Console]::Error.WriteLine('失败: 端点没有返回任何内容（游戏可能正在加载或已退出）。')
    exit 3
}

Write-Output $reply
if ($reply.StartsWith('error:')) { exit 4 }
exit 0
