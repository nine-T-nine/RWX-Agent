# Batch driver: each non-empty, non-# line is "command<TAB>arg1<TAB>arg2...".
# Usage: .\rwx-agent-batch.ps1 -File cmds.txt [-Port N]
param(
    [Parameter(Mandatory = $true)][string]$File,
    [int]$Port = 0,
    [string]$LogFile
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../../../agent-operation/tools/rwx-cli-lib.ps1')
if ($Port -eq 0) {
    $endpoint = Resolve-RwxEndpoint -Log $LogFile
    if ($endpoint) { $Port = $endpoint.Port }
}
if ($Port -eq 0) {
    if ($env:RWX_CLI_PORT) { $Port = [int]$env:RWX_CLI_PORT }
    elseif (Test-Path $LogFile) {
        $m = Select-String -Path $LogFile -Pattern 'RWX_CLI_TOOL_READY port=(\d+)' | Select-Object -Last 1
        if ($m) { $Port = [int]$m.Matches[0].Groups[1].Value }
    }
}
if ($Port -eq 0) { throw 'RWX CLI port not found.' }

function Send-Rwx([string]$command, [string[]]$argv) {
    $encoded = @($argv | ForEach-Object { [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($_)) }) -join ','
    $client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $Port)
    try {
        $client.ReceiveTimeout = 60000
        $stream = $client.GetStream()
        $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
        $writer.AutoFlush = $true
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
        $writer.WriteLine($command + [char]9 + $encoded)
        $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
        $reader.ReadToEnd().TrimEnd()
    } finally { $client.Dispose() }
}

foreach ($raw in Get-Content -LiteralPath $File -Encoding UTF8) {
    $line = $raw.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { continue }
    $parts = $line -split "`t"
    $args = @()
    if ($parts.Count -gt 1) { $args = $parts[1..($parts.Count - 1)] }
    Write-Output ("### " + ($parts -join ' '))
    try { Write-Output (Send-Rwx $parts[0] $args) } catch { Write-Output ("error: " + $_.Exception.Message) }
    Write-Output ''
}
