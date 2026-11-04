# rwx-cli-lib.ps1 — rwx-cli.ps1 / rwx-cli-daemon.ps1 共用的端点发现与连接代码。
#
# 协议要点（与 game 里的 RwxCliTool 对应）：
#   * 一次性客户端：发一行 `<命令><TAB><base64 参数逗号分隔>`，半关闭写端，读到 EOF。
#   * 常驻客户端：先发一行 `!!framed`，之后每条回复都用 ASCII RS(0x1E) 包起来；
#     这样多行输出不需要“结束标记”，一条 TCP 连接可以连续跑很多命令。
#
# 脚本内不写中文注释以外的东西，所有函数都只依赖 .NET 2.0 就有的类型，Windows PowerShell 5.1 可用。

function Get-RwxLoopbackListeners {
    param([Parameter(Mandatory = $true)][int]$ProcessId)
    if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) { return @() }
    $ports = New-Object System.Collections.Generic.List[int]
    # netstat 比 Get-NetTCPConnection 更可靠：不需要管理员权限也能看到“本用户进程”的端点。
    $netstat = & "$env:SystemRoot\System32\netstat.exe" -ano 2>$null
    foreach ($line in $netstat) {
        if ($line -notmatch 'LISTENING') { continue }
        $parts = ($line.Trim() -split '\s+')
        if ($parts.Count -lt 5) { continue }
        if ([int]$parts[4] -ne $ProcessId) { continue }
        $local = $parts[1]
        if ($local -notmatch '^(127\.0\.0\.1|\[?::1\]?|0\.0\.0\.0|\[?::\]?):(\d+)$') { continue }
        $candidate = [int]$Matches[2]
        if (-not $ports.Contains($candidate)) { $ports.Add($candidate) }
    }
    return $ports
}

function Test-RwxEndpoint {
    param(
        [Parameter(Mandatory = $true)][int]$CandidatePort,
        [int]$TimeoutMilliseconds = 1500
    )
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $task = $client.ConnectAsync('127.0.0.1', $CandidatePort)
        if (-not $task.Wait($TimeoutMilliseconds)) { $client.Dispose(); return $false }
        $client.ReceiveTimeout = $TimeoutMilliseconds
        $stream = $client.GetStream()
        $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
        $writer.AutoFlush = $true
        $writer.WriteLine("rwx-ping`t")
        $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $reply = $reader.ReadToEnd().Trim()
        $client.Dispose()
        return ($reply -eq 'pong')
    } catch {
        return $false
    } finally {
        if ($client) { $client.Dispose() }
    }
}

# Port-file candidates: explicit env/property path, then the checkout root discovered by walking
# up from this script, then the current directory.
function Get-RwxPortFileCandidates {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:RWX_CLI_PORT_FILE) { return @($env:RWX_CLI_PORT_FILE) }
    $candidates.Add((Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp/port'))
    $dir = $PSScriptRoot
    for ($i = 0; $i -lt 8 -and $dir; $i++) {
        if (Test-Path (Join-Path $dir 'settings.gradle.kts')) {
            $candidates.Add((Join-Path $dir 'agent-operation/tmp/port'))
            break
        }
        $parent = Split-Path -Parent $dir
        if ($parent -eq $dir) { break }
        $dir = $parent
    }
    $candidates.Add((Join-Path (Get-Location) 'agent-operation/tmp/port'))
    return ($candidates | Select-Object -Unique)
}

<#
查找游戏的 CLI 端点。显式 -Port / 进程选择优先；默认先读 agent-operation/tmp/port。
返回 $null 表示没找到。
#>
function Resolve-RwxEndpoint {
    param(
        [int]$Port = 0,
        [int]$Id = 0,
        [string]$ProcessName,
        [string]$Log,
        [string[]]$DefaultLogs = @('.\rwx-agent-run.log')
    )
    if ($Port -gt 0) { return [pscustomobject]@{ Port = $Port; Source = 'parameter' } }

    # Prefer the published CLI file. PID/name overrides explicitly select a different instance.
    if ($Id -le 0 -and -not $ProcessName) {
        foreach ($portFile in (Get-RwxPortFileCandidates)) {
            if (-not (Test-Path -LiteralPath $portFile)) { continue }
            $raw = [string](Get-Content -LiteralPath $portFile -Raw -ErrorAction SilentlyContinue)
            $parsed = 0
            if ([int]::TryParse($raw.Trim(), [ref]$parsed) -and $parsed -ge 1 -and $parsed -le 65535 -and
                (Test-RwxEndpoint -CandidatePort $parsed)) {
                return [pscustomobject]@{ Port = $parsed; Source = "port file $portFile" }
            }
        }
        if ($env:RWX_CLI_PORT_FILE) { return $null }
        $instancePorts = @()
        foreach ($portFile in (Get-RwxPortFileCandidates)) {
            $instances = "$portFile.instances"
            if (-not (Test-Path -LiteralPath $instances)) { continue }
            foreach ($file in (Get-ChildItem -LiteralPath $instances -Filter '*.port' -File)) {
                $parsed = 0
                $raw = [string](Get-Content -LiteralPath $file.FullName -Raw -ErrorAction SilentlyContinue)
                if ([int]::TryParse($raw.Trim(), [ref]$parsed) -and $parsed -ge 1 -and $parsed -le 65535 -and
                    (Test-RwxEndpoint -CandidatePort $parsed)) { $instancePorts += $parsed }
            }
        }
        $instancePorts = @($instancePorts | Select-Object -Unique)
        if ($instancePorts.Count -gt 1) { throw 'Multiple RWX instances; select -Id, -Port or RWX_CLI_PORT_FILE explicitly.' }
        if ($instancePorts.Count -eq 1) { return [pscustomobject]@{ Port = $instancePorts[0]; Source = 'instance port file' } }
    }

    $pids = @()
    if ($Id -gt 0) {
        $pids = @($Id)
    } else {
        $names = if ($ProcessName) { @($ProcessName -replace '\.exe$', '') } else { @('rwx', 'RWX', 'java') }
        foreach ($n in $names) {
            $found = @(Get-Process -Name $n -ErrorAction SilentlyContinue)
            if ($n -eq 'java') {
                # 打包版通常是 rwx.exe；开发版是 java.exe，用窗口标题区分。
                $found = @($found | Where-Object { $_.MainWindowTitle -like '*RWX*' })
            }
            $pids += @($found | ForEach-Object { $_.Id })
        }
    }

    foreach ($candidatePid in ($pids | Select-Object -Unique)) {
        foreach ($candidatePort in (Get-RwxLoopbackListeners -ProcessId $candidatePid)) {
            if (Test-RwxEndpoint -CandidatePort $candidatePort) {
                return [pscustomobject]@{ Port = $candidatePort; Source = "pid $candidatePid listener" }
            }
        }
    }
    if ($Id -gt 0 -or $ProcessName) { return $null }

    $logs = @()
    if ($Log) { $logs = @($Log) } else { $logs = $DefaultLogs }
    foreach ($logPath in $logs) {
        if (-not (Test-Path $logPath)) { continue }
        $match = Select-String -Path $logPath -Pattern 'RWX_CLI_TOOL_READY port=(\d+)' -Encoding UTF8 |
            Select-Object -Last 1
        if (-not $match) { continue }
        $fromLog = [int]$match.Matches[0].Groups[1].Value
        if (Test-RwxEndpoint -CandidatePort $fromLog) {
            return [pscustomobject]@{ Port = $fromLog; Source = "log $logPath" }
        }
    }

    return $null
}

# 把“命令行字符串”拆成 argv。支持双引号（值里有空格时用），不做转义之外的魔法。
function ConvertFrom-RwxCommandLine {
    param([Parameter(Mandatory = $true)][string]$Line)
    $tokens = New-Object System.Collections.Generic.List[string]
    $current = New-Object System.Text.StringBuilder
    $inQuotes = $false
    $hasToken = $false
    for ($i = 0; $i -lt $Line.Length; $i++) {
        $ch = $Line[$i]
        if ($ch -eq '"') { $inQuotes = -not $inQuotes; $hasToken = $true; continue }
        if (-not $inQuotes -and [char]::IsWhiteSpace($ch)) {
            if ($hasToken) { $tokens.Add($current.ToString()); [void]$current.Clear(); $hasToken = $false }
            continue
        }
        [void]$current.Append($ch)
        $hasToken = $true
    }
    if ($hasToken) { $tokens.Add($current.ToString()) }
    if ($tokens.Count -eq 0) { throw '空命令行' }
    return , $tokens.ToArray()
}

# 一次性请求行：命令 + TAB + base64(argv) 逗号分隔。
function New-RwxRequestLine {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Arguments = @()
    )
    $encoded = @($Arguments | ForEach-Object {
        if ($null -eq $_) { '' } else {
            [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes([string]$_))
        }
    }) -join ','
    return ("{0}`t{1}" -f $Command, $encoded)
}

# 建立 framed 会话：连上、握手、读掉 ready 帧。
function Connect-RwxFramed {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [int]$TimeoutMs = 30000
    )
    $client = New-Object System.Net.Sockets.TcpClient
    $client.Connect('127.0.0.1', $Port)
    $client.ReceiveTimeout = $TimeoutMs
    $client.NoDelay = $true
    $stream = $client.GetStream()
    $writer = New-Object System.IO.StreamWriter($stream, (New-Object System.Text.UTF8Encoding($false)))
    $writer.AutoFlush = $true
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    $writer.WriteLine('!!framed')
    $ready = Read-RwxFramedReply -Reader $reader -TimeoutMs $TimeoutMs
    if ($ready -ne 'ready') {
        $writer.Dispose(); $reader.Dispose(); $client.Dispose()
        throw "端点没有按 framed 协议应答（收到 '$ready'）"
    }
    return [pscustomobject]@{ Client = $client; Writer = $writer; Reader = $reader; Port = $Port }
}

# 读一条 RS 包起来的回复。
function Read-RwxFramedReply {
    param(
        [Parameter(Mandatory = $true)]$Reader,
        [int]$TimeoutMs = 60000
    )
    $rs = [char]0x1e
    # 跳过回复前的杂字节，直到开头的 RS。
    while ($true) {
        $code = $Reader.Read()
        if ($code -lt 0) { throw '连接已断开（未收到回复帧）' }
        if ([char]$code -eq $rs) { break }
    }
    $builder = New-Object System.Text.StringBuilder
    while ($true) {
        $code = $Reader.Read()
        if ($code -lt 0) { throw '连接已断开（回复帧不完整）' }
        if ([char]$code -eq $rs) { break }
        [void]$builder.Append([char]$code)
    }
    return $builder.ToString()
}

function Close-RwxFramed {
    param($Session)
    if ($null -eq $Session) { return }
    try { $Session.Writer.Dispose() } catch { }
    try { $Session.Reader.Dispose() } catch { }
    try { $Session.Client.Dispose() } catch { }
}

# 在已建立的 framed 会话上执行一条命令。
function Invoke-RwxFramedCommand {
    param(
        [Parameter(Mandatory = $true)]$Session,
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Arguments = @(),
        [int]$TimeoutMs = 60000
    )
    $Session.Client.ReceiveTimeout = $TimeoutMs
    $Session.Writer.WriteLine((New-RwxRequestLine -Command $Command -Arguments $Arguments))
    return (Read-RwxFramedReply -Reader $Session.Reader -TimeoutMs $TimeoutMs)
}
