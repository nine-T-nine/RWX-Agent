# Agent-side driver for the RWX CLI tool (see rwx_cli_tool/help.md).
# Usage:  .\rwx-agent-cli.ps1 -Command rwx-game-state -Args '--summary'
#         .\rwx-agent-cli.ps1 -Command rwx-battleroom-chat -Args '"hello world"'
param(
    [Parameter(Mandatory = $true)][string]$Command,
    [string[]]$Args = @(),
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
    if ($env:RWX_CLI_PORT) {
        $Port = [int]$env:RWX_CLI_PORT
    } elseif (Test-Path $LogFile) {
        $match = Select-String -Path $LogFile -Pattern 'RWX_CLI_TOOL_READY port=(\d+)' | Select-Object -Last 1
        if ($match) { $Port = [int]$match.Matches[0].Groups[1].Value }
    }
}
if ($Port -eq 0) { throw 'RWX CLI port not found. Start :desktop:rwxCliRun first.' }

$encoded = @($Args | ForEach-Object { [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($_)) }) -join ','
$client = [System.Net.Sockets.TcpClient]::new('127.0.0.1', $Port)
try {
    $client.ReceiveTimeout = 60000
    $stream = $client.GetStream()
    $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
    $writer.AutoFlush = $true
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    $writer.WriteLine($Command + [char]9 + $encoded)
    $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
    $reader.ReadToEnd().TrimEnd()
} finally { $client.Dispose() }
