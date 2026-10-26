<#
.SYNOPSIS
    对正在运行的游戏进程截图，窗口被其它窗口遮挡也能截，图片默认存到当前目录的 tmp\ 下。

.DESCRIPTION
    工作方式（按顺序尝试，前一步成功就不做下一步）：

      0. （-Method auto 且窗口是 GL/画布类时）直接走第 4 步的置顶抓屏。PrintWindow 在这类窗口上
         会静默返回上一帧，见下方 -Method 的说明。
      1. PrintWindow(PW_RENDERFULLCONTENT)  —— 让窗口把自己画到内存 DC 里，不依赖窗口是否可见/置顶，
         不会抢焦点，也不会闪烁。GDI 窗口、多数 OpenGL 窗口（含 LWJGL 游戏）在这一步就能拿到内容。
      2. PrintWindow(0)                     —— 老式重绘路径，个别老程序只认这个。
      3. 子窗口                              —— 有些框架（AWT/GL、Chromium）把画面放在子 HWND 里，
         顶层窗口只有边框，这时对子窗口重复 1、2。
      4. 短暂置顶 + 抓屏                      —— 如果目标窗口是 GPU 直连/独占渲染（PrintWindow 只能得到纯黑），
         且它没有被最小化，就把窗口临时提到最前（不激活、不改大小），抓屏后把它放回原来的 z 序位置。
         用 -NoRaise 可以禁用这一步。

    只有“进程没有可见窗口”或“窗口被最小化/在别的虚拟桌面”时才会失败——这与需求一致。

.PARAMETER Name
    进程名，可带或不带 .exe，例如 rwx、rwx.exe、java。与 -Id 二选一。

.PARAMETER Id
    进程 ID（PID）。与 -Name 二选一。

.PARAMETER Title
    按窗口标题定位目标。可以单独使用（不需要 -Name/-Id）：不区分大小写的子串匹配，
    前后空格忽略；含 * 或 ? 时按通配符处理（如 'RWX*'、'*Game*'）。
    例如 -Title "RWX Game"。和 -Name/-Id 一起用时表示“在这个进程里按标题挑窗口”。

.PARAMETER OutDir
    输出目录，默认 .\tmp（相对当前工作目录），不存在会自动创建。

.PARAMETER Out
    直接指定输出文件路径（.png）。默认按 进程名_时间戳 命名。

.PARAMETER All
    对目标进程的每个可见顶层窗口都截一张。

.PARAMETER List
    只列出候选窗口（hwnd、pid、面积、是否最小化、标题），不截图。

.PARAMETER NoRaise
    禁用第 4 步（临时置顶 + 抓屏）。PrintWindow 拿不到内容时就直接报错，绝不把窗口翻上来。

.PARAMETER NoChild
    禁用第 3 步（子窗口回退），只试顶层窗口。

.PARAMETER ClientOnly
    只保存客户区（去掉标题栏和边框），适合要图片本身而不是“窗口截图”的场景。
    对子窗口截图本来就没有边框，这个开关不影响它。

.PARAMETER RestoreIfMinimized
    目标窗口最小化时，先临时恢复它、截完再最小化回去。默认关闭：
    最小化属于“没出现在桌面上”，工具不会擅自动用户的窗口。

.PARAMETER DelayMs
    截图前等待的毫秒数（默认 0）。游戏正在加载/换帧时可以给 300~1000。

.PARAMETER Method
    取图方式。`auto`（默认）会看窗口类：OpenGL/画布类窗口（SunAwtFrame、GLFW、LWJGL、SDL）
    直接用「置顶 + 抓屏」，其余窗口仍按 PrintWindow → 子窗口 → 置顶抓屏的顺序试。
    `printwindow` 只用 PrintWindow（旧行为）；`screen` 只用置顶抓屏。

    为什么要这样：PrintWindow 对 GL 窗口返回的是 **DWM 上一次为该窗口合成的表面**。
    窗口被挡住时 DWM 不再为它合成，于是拿到的是上一帧画面——而且旧帧颜色丰富，
    “是否纯黑”的检查拦不住它，最终会静默保存一张过期截图（小地图看着是新的，主视图还是旧视野）。
    置顶后从屏幕抓取才是当前帧。

.PARAMETER SettleMs
    置顶之后、真正抓屏之前的等待毫秒数（默认 250）。游戏刚移动过视野时给 400~800 更稳。

.PARAMETER ForceForeground
    置顶时同时调用 SetForegroundWindow 抢前台。默认不做（本工具原本不抢焦点）；
    只有当置顶后仍抓不到新画面（例如有别的置顶窗口压着）时才需要。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File screenshot.ps1 -Name rwx

.EXAMPLE
    .\screenshot.ps1 -Id 25156 -OutDir .\tmp

.EXAMPLE
    .\screenshot.ps1 -Name java -Title RWX -DelayMs 500

.EXAMPLE
    .\screenshot.ps1 -Name rwx -ClientOnly -Out .\tmp\now.png

.EXAMPLE
    .\screenshot.ps1 -Title "RWX Game" -RestoreIfMinimized

.NOTES
    Windows PowerShell 5.1+（powershell.exe）。不需要 Python，也不依赖第三方模块。
    退出码：0 成功；2 找不到进程；3 进程没有可见窗口；4 截图失败（窗口最小化/无法读取）。
#>
[CmdletBinding()]
param(
    [string]$Name,
    [int]$Id = 0,
    [string]$Title,
    [string]$OutDir,
    [string]$Out,
    [switch]$All,
    [switch]$List,
    [switch]$NoRaise,
    [switch]$NoChild,
    [switch]$ClientOnly,
    [switch]$RestoreIfMinimized,
    [ValidateSet('auto', 'printwindow', 'screen')][string]$Method = 'auto',
    [int]$SettleMs = 250,
    [switch]$ForceForeground,
    [int]$DelayMs = 0
)

$ErrorActionPreference = 'Stop'

if ($Id -le 0 -and [string]::IsNullOrWhiteSpace($Name) -and [string]::IsNullOrWhiteSpace($Title)) {
    throw '请用 -Name <进程名>、-Id <PID> 或 -Title <窗口标题> 指定目标。'
}
if ($Id -le 0 -and [string]::IsNullOrWhiteSpace($Name) -and $All) {
    throw '-All 需要配合 -Name 或 -Id 使用（只给标题时请用 -Title 精确指到窗口）。'
}

Add-Type -AssemblyName System.Drawing

if (-not ([System.Management.Automation.PSTypeName]'RwxWin32').Type) {
    Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;

public static class RwxWin32 {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
    [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr p);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr hWnd, uint cmd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern IntPtr GetDC(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern int ReleaseDC(IntPtr hWnd, IntPtr hdc);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hWnd, out RECT r);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr hWnd, ref POINT p);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr ctx);

    [DllImport("gdi32.dll")] public static extern IntPtr CreateCompatibleDC(IntPtr hdc);
    [DllImport("gdi32.dll")] public static extern IntPtr CreateCompatibleBitmap(IntPtr hdc, int w, int h);
    [DllImport("gdi32.dll")] public static extern IntPtr SelectObject(IntPtr hdc, IntPtr obj);
    [DllImport("gdi32.dll")] public static extern bool DeleteObject(IntPtr obj);
    [DllImport("gdi32.dll")] public static extern bool DeleteDC(IntPtr hdc);
    [DllImport("gdi32.dll")] public static extern bool PatBlt(IntPtr hdc, int x, int y, int w, int h, uint rop);

    [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out RECT value, int size);

    public const uint GW_OWNER = 4;
    public const uint GW_HWNDPREV = 3;
    public const int DWMWA_EXTENDED_FRAME_BOUNDS = 9;
    public const uint PW_RENDERFULLCONTENT = 0x2;
    public const uint SWP_NOSIZE = 0x1, SWP_NOMOVE = 0x2, SWP_NOACTIVATE = 0x10;
    public static readonly IntPtr HWND_TOP = IntPtr.Zero;
    public static readonly IntPtr HWND_NOTOPMOST = new IntPtr(-2);
    public const uint BLACKNESS = 0x42;

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    public struct POINT { public int X, Y; }
}
'@
}

try { [void][RwxWin32]::SetProcessDpiAwarenessContext([IntPtr](-4)) } catch { try { [void][RwxWin32]::SetProcessDPIAware() } catch { } }

# ---------------------------------------------------------------- 目标进程

function Resolve-TargetProcess {
    param([int]$ProcessId, [string]$ProcessName)
    if ($ProcessId -le 0 -and [string]::IsNullOrWhiteSpace($ProcessName)) {
        # 只给了 -Title：不限定进程，交给窗口标题匹配。
        return @()
    }
    if ($ProcessId -gt 0) {
        $p = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
        if (-not $p) { throw "找不到 PID $ProcessId 对应的进程。" }
        return @($p)
    }
    $clean = $ProcessName -replace '\.exe$', ''
    $list = @(Get-Process -Name $clean -ErrorAction SilentlyContinue)
    if ($list.Count -eq 0) {
        # 也允许按标题/窗口名碰运气：进程名可能被重命名过
        $list = @(Get-Process | Where-Object { $_.ProcessName -like "*$clean*" })
    }
    if ($list.Count -eq 0) { throw "找不到进程 '$ProcessName'。" }
    return $list
}

# ---------------------------------------------------------------- 窗口枚举

function Get-TopWindows {
    param([int[]]$Pids)
    $result = New-Object System.Collections.ArrayList
    $cb = [RwxWin32+EnumProc]{
        param($hwnd, $lparam)
        if (-not [RwxWin32]::IsWindowVisible($hwnd)) { return $true }
        $pid2 = [uint32]0
        [void][RwxWin32]::GetWindowThreadProcessId($hwnd, [ref]$pid2)
        if (@($Pids).Count -gt 0 -and (@($Pids) -notcontains [int]$pid2)) { return $true }

        $len = [RwxWin32]::GetWindowTextLength($hwnd)
        $sb = New-Object System.Text.StringBuilder ($len + 1)
        [void][RwxWin32]::GetWindowText($hwnd, $sb, $sb.Capacity)
        $cls = New-Object System.Text.StringBuilder 256
        [void][RwxWin32]::GetClassName($hwnd, $cls, 256)

        $r = New-Object RwxWin32+RECT
        $rc = Get-WindowRectDwm $hwnd
        [void]$result.Add([pscustomobject]@{
            Hwnd      = [int64]$hwnd
            Pid       = [int]$pid2
            Title     = $sb.ToString()
            Class     = $cls.ToString()
            Left      = $rc.Left
            Top       = $rc.Top
            Width     = [Math]::Max(0, $rc.Right - $rc.Left)
            Height    = [Math]::Max(0, $rc.Bottom - $rc.Top)
            Minimized = [RwxWin32]::IsIconic($hwnd)
            Owner     = [int64][RwxWin32]::GetWindow($hwnd, [RwxWin32]::GW_OWNER)
        })
        return $true
    }
    [void][RwxWin32]::EnumWindows($cb, [IntPtr]::Zero)
    return $result
}

function Get-WindowRectDwm {
    param([IntPtr]$Hwnd)
    $r = New-Object RwxWin32+RECT
    $hr = [RwxWin32]::DwmGetWindowAttribute($Hwnd, [RwxWin32]::DWMWA_EXTENDED_FRAME_BOUNDS, [ref]$r, [System.Runtime.InteropServices.Marshal]::SizeOf($r))
    if ($hr -ne 0 -or ($r.Right - $r.Left) -le 0 -or ($r.Bottom - $r.Top) -le 0) {
        [void][RwxWin32]::GetWindowRect($Hwnd, [ref]$r)
    }
    return $r
}

function Get-ChildWindows {
    param([IntPtr]$Parent)
    $result = New-Object System.Collections.ArrayList
    $cb = [RwxWin32+EnumProc]{
        param($hwnd, $lparam)
        if (-not [RwxWin32]::IsWindowVisible($hwnd)) { return $true }
        $r = Get-WindowRectDwm $hwnd
        $w = $r.Right - $r.Left
        $h = $r.Bottom - $r.Top
        if ($w -lt 16 -or $h -lt 16) { return $true }
        [void]$result.Add([pscustomobject]@{
            Hwnd  = [int64]$hwnd
            Left  = $r.Left
            Top   = $r.Top
            Width = $w
            Height = $h
        })
        return $true
    }
    [void][RwxWin32]::EnumChildWindows($Parent, $cb, [IntPtr]::Zero)
    return $result
}

<#
  标题匹配：不含 * 或 ? 时按“不区分大小写的子串”匹配（前后空格忽略）；
  含通配符时按 -like 处理，例如 'RWX*' 或 '*Game*'。
#>
function Test-TitleMatch {
    param([string]$WindowTitle, [string]$Pattern)
    if ([string]::IsNullOrWhiteSpace($Pattern)) { return $true }
    $text = if ($null -eq $WindowTitle) { '' } else { $WindowTitle.Trim() }
    $want = $Pattern.Trim()
    if ($want.Contains('*') -or $want.Contains('?')) { return $text -like $want }
    return $text.IndexOf($want, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
}

# ---------------------------------------------------------------- 截图核心

function Invoke-PrintWindow {
    param([int64]$Hwnd, [uint32]$Flags)
    $h = [IntPtr]$Hwnd
    $rc = Get-WindowRectDwm $h
    $w = $rc.Right - $rc.Left
    $ht = $rc.Bottom - $rc.Top
    if ($w -le 0 -or $ht -le 0) { return $null }

    $screenDc = [RwxWin32]::GetDC($h)
    if ($screenDc -eq [IntPtr]::Zero) { return $null }
    $memDc = [IntPtr]::Zero
    $hBmp = [IntPtr]::Zero
    $old = [IntPtr]::Zero
    try {
        $memDc = [RwxWin32]::CreateCompatibleDC($screenDc)
        $hBmp = [RwxWin32]::CreateCompatibleBitmap($screenDc, $w, $ht)
        if ($memDc -eq [IntPtr]::Zero -or $hBmp -eq [IntPtr]::Zero) { return $null }
        $old = [RwxWin32]::SelectObject($memDc, $hBmp)
        [void][RwxWin32]::PatBlt($memDc, 0, 0, $w, $ht, [RwxWin32]::BLACKNESS)
        $ok = [RwxWin32]::PrintWindow($h, $memDc, $Flags)
        if (-not $ok) { return $null }
        return New-Object System.Drawing.Bitmap ([System.Drawing.Image]::FromHbitmap($hBmp))
    } finally {
        if ($old -ne [IntPtr]::Zero) { [void][RwxWin32]::SelectObject($memDc, $old) }
        if ($hBmp -ne [IntPtr]::Zero) { [void][RwxWin32]::DeleteObject($hBmp) }
        if ($memDc -ne [IntPtr]::Zero) { [void][RwxWin32]::DeleteDC($memDc) }
        if ($screenDc -ne [IntPtr]::Zero) { [void][RwxWin32]::ReleaseDC($h, $screenDc) }
    }
}

function Test-BlankBitmap {
    param([System.Drawing.Bitmap]$Bitmap)
    $small = New-Object System.Drawing.Bitmap 24, 24
    $g = [System.Drawing.Graphics]::FromImage($small)
    try {
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBilinear
        $g.DrawImage($Bitmap, 0, 0, 24, 24)
    } finally { $g.Dispose() }
    try {
        $first = $null
        for ($y = 0; $y -lt 24; $y += 3) {
            for ($x = 0; $x -lt 24; $x += 3) {
                $c = $small.GetPixel($x, $y)
                if ($null -eq $first) { $first = $c.ToArgb() }
                elseif ($c.ToArgb() -ne $first) { return $false }
            }
        }
        return $true
    } finally { $small.Dispose() }
}

function Invoke-ScreenGrab {
    param([int]$Left, [int]$Top, [int]$Width, [int]$Height)
    if ($Width -le 0 -or $Height -le 0) { return $null }
    $bmp = New-Object System.Drawing.Bitmap $Width, $Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    try {
        $g.CopyFromScreen($Left, $Top, 0, 0, (New-Object System.Drawing.Size $Width, $Height))
    } catch {
        $g.Dispose(); $bmp.Dispose(); return $null
    } finally { $g.Dispose() }
    return $bmp
}

<#
  临时把窗口提到最前抓屏，然后按原来的 z 序位置放回去。
  默认不激活窗口（SWP_NOACTIVATE），也不改大小/位置；-ForceForeground 时才调用
  SetForegroundWindow。置顶后要等一小会儿（-SettleMs），DWM 重新合成之后屏幕上的像素才是当前帧。
#>
function Invoke-RaiseAndGrab {
    param($Window, [int]$Settle = 250, [switch]$Foreground)
    $h = [IntPtr]$Window.Hwnd
    $before = [RwxWin32]::GetWindow($h, [RwxWin32]::GW_HWNDPREV)
    [void][RwxWin32]::SetWindowPos($h, [RwxWin32]::HWND_TOP, 0, 0, 0, 0,
        ([RwxWin32]::SWP_NOMOVE -bor [RwxWin32]::SWP_NOSIZE -bor [RwxWin32]::SWP_NOACTIVATE))
    if ($Foreground) {
        [void][RwxWin32]::BringWindowToTop($h)
        [void][RwxWin32]::SetForegroundWindow($h)
    }
    try {
        if ($Settle -gt 0) { Start-Sleep -Milliseconds $Settle }
        $bmp = Invoke-ScreenGrab -Left $Window.Left -Top $Window.Top -Width $Window.Width -Height $Window.Height
        return $bmp
    } finally {
        # 放回原来那张窗口下面；GetWindow 返回 0 时落到 HWND_TOP 也行，但通常能恢复。
        $after = if ($before -ne [IntPtr]::Zero) { $before } else { [RwxWin32]::HWND_NOTOPMOST }
        [void][RwxWin32]::SetWindowPos($h, $after, 0, 0, 0, 0,
            ([RwxWin32]::SWP_NOMOVE -bor [RwxWin32]::SWP_NOSIZE -bor [RwxWin32]::SWP_NOACTIVATE))
    }
}

<#
  OpenGL / 画布类窗口：PrintWindow 拿不到当前帧，直接走置顶抓屏。
  游戏（LWJGL3 + AWT）的窗口类是 SunAwtFrame；其余按常见 GL 框架类名兜底。
#>
function Test-OpenGlCanvasClass {
    param([string]$ClassName)
    if ([string]::IsNullOrWhiteSpace($ClassName)) { return $false }
    return $ClassName -match 'SunAwtFrame|GLFW|LWJGL|SDL|SunAwtCanvas'
}

function ConvertTo-FlatBitmap {
    param([System.Drawing.Bitmap]$Bitmap)
    # PrintWindow 出来的 32bpp 位图 alpha 常为 0，直接存 PNG 会全透明；垫一层白底。
    $flat = New-Object System.Drawing.Bitmap $Bitmap.Width, $Bitmap.Height, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($flat)
    try {
        $g.Clear([System.Drawing.Color]::White)
        $g.DrawImage($Bitmap, 0, 0, $Bitmap.Width, $Bitmap.Height)
    } finally { $g.Dispose() }
    return $flat
}

<#
  只保留客户区：从窗口截图里按客户区偏移裁掉标题栏和边框。
  传入的 Bitmap 必须是“整个窗口”的截图（顶层窗口），子窗口截图本身就是客户区。
#>
function Crop-ClientArea {
    param([System.Drawing.Bitmap]$Bitmap, [int64]$Hwnd)
    $h = [IntPtr]$Hwnd
    $rc = Get-WindowRectDwm $h
    $pt = New-Object RwxWin32+POINT
    if (-not [RwxWin32]::ClientToScreen($h, [ref]$pt)) { return $Bitmap }
    $cr = New-Object RwxWin32+RECT
    if (-not [RwxWin32]::GetClientRect($h, [ref]$cr)) { return $Bitmap }
    $w = $cr.Right - $cr.Left
    $ht = $cr.Bottom - $cr.Top
    $ox = $pt.X - $rc.Left
    $oy = $pt.Y - $rc.Top
    if ($w -le 0 -or $ht -le 0 -or $ox -lt 0 -or $oy -lt 0 -or
        ($ox + $w) -gt $Bitmap.Width -or ($oy + $ht) -gt $Bitmap.Height) {
        return $Bitmap
    }
    $rect = New-Object System.Drawing.Rectangle $ox, $oy, $w, $ht
    return $Bitmap.Clone($rect, $Bitmap.PixelFormat)
}

function Save-Shot {
    param([System.Drawing.Bitmap]$Bitmap, [string]$Path)
    $flat = ConvertTo-FlatBitmap $Bitmap
    try {
        $dir = Split-Path -Parent $Path
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        $flat.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $flat.Dispose() }
}

# ---------------------------------------------------------------- 主流程

try {
    $processes = Resolve-TargetProcess -ProcessId $Id -ProcessName $Name
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 2
}
$pids = @($processes | ForEach-Object { $_.Id })
$windows = @(Get-TopWindows -Pids $pids)

if (-not [string]::IsNullOrWhiteSpace($Title)) {
    $windows = @($windows | Where-Object { Test-TitleMatch -WindowTitle $_.Title -Pattern $Title })
}

if ($List) {
    if ($windows.Count -eq 0) {
        if ($Title) { Write-Output "没有标题匹配 '$Title' 的可见窗口。" } else { Write-Output '没有可见的顶层窗口。' }
        exit 3
    }
    $names = @{}
    foreach ($proc in (Get-Process -ErrorAction SilentlyContinue)) { $names[[int]$proc.Id] = $proc.ProcessName }
    $rows = $windows | Sort-Object -Property @{Expression = { $_.Width * $_.Height }} -Descending |
        ForEach-Object {
            [pscustomobject]@{
                Hwnd      = ('0x{0:X}' -f $_.Hwnd)
                Pid       = $_.Pid
                Process   = $names[$_.Pid]
                Minimized = $_.Minimized
                Width     = $_.Width
                Height    = $_.Height
                Class     = $_.Class
                Title     = $_.Title
            }
        }
    $rows | Format-Table -AutoSize | Out-String -Width 260
    exit 0
}

if ($windows.Count -eq 0) {
    # 用 Console.Error 而不是 Write-Error：$ErrorActionPreference='Stop' 会把 Write-Error 变成
    # 终止错误，退出码会变成 1，调用方就分不清“没窗口”和“脚本崩了”。
    if ($Title -and $pids.Count -eq 0) {
        [Console]::Error.WriteLine("没有标题包含 '$Title' 的可见窗口（用 -List -Title <片段> 可以看实际标题）。")
    } elseif ($Title) {
        [Console]::Error.WriteLine("进程 $($pids -join ',') 里没有标题包含 '$Title' 的可见窗口（用 -List 看实际标题）。")
    } else {
        [Console]::Error.WriteLine("进程 $($pids -join ',') 没有可见的顶层窗口（可能最小化到托盘、隐藏或不在当前桌面）。")
    }
    exit 3
}

# 有 owner 的通常是对话框/工具窗；没有可用的无 owner 窗口时才退回去用它们。
$preferred = @($windows | Where-Object { $_.Owner -eq 0 })
if ($preferred.Count -eq 0) { $preferred = @($windows) }

$targets = if ($All) {
    @($preferred | Sort-Object -Property @{Expression = { $_.Width * $_.Height }} -Descending)
} else {
    @($preferred | Sort-Object -Property @{Expression = { $_.Width * $_.Height }} -Descending | Select-Object -First 1)
}

if ($DelayMs -gt 0) { Start-Sleep -Milliseconds $DelayMs }

if (-not $OutDir -and -not $Out) { $OutDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'tmp' }
if ($OutDir -and -not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

$stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$index = 0
$saved = New-Object System.Collections.ArrayList

foreach ($w in $targets) {
    $index++
    $captureMethod = $null
    $capturedHwnd = $w.Hwnd
    $bmp = $null
    $restored = $false

    if ($w.Minimized -and $RestoreIfMinimized) {
        # 显式要求时才会动窗口：恢复 -> 截图 -> 再最小化。
        [void][RwxWin32]::ShowWindow([IntPtr]$w.Hwnd, 9)   # SW_RESTORE
        Start-Sleep -Milliseconds 350
        $restored = $true
        $rc2 = Get-WindowRectDwm ([IntPtr]$w.Hwnd)
        $w.Left = $rc2.Left
        $w.Top = $rc2.Top
        $w.Width = $rc2.Right - $rc2.Left
        $w.Height = $rc2.Bottom - $rc2.Top
        $w.Minimized = $false
    }

    try {
    if (-not $w.Minimized) {
        # OpenGL / 画布类窗口的 PrintWindow 会静默返回上一帧（见文件头说明），所以默认先抓屏。
        $preferScreen = ($Method -eq 'screen') -or
            ($Method -eq 'auto' -and (Test-OpenGlCanvasClass $w.Class))
        if ($preferScreen -and -not $NoRaise) {
            $bmp = Invoke-RaiseAndGrab -Window $w -Settle $SettleMs -Foreground:$ForceForeground
            if ($bmp -and -not (Test-BlankBitmap $bmp)) { $captureMethod = 'raise+screen' } else { if ($bmp) { $bmp.Dispose() }; $bmp = $null }
        }

        if (-not $bmp -and $Method -ne 'screen') {
            $bmp = Invoke-PrintWindow -Hwnd $w.Hwnd -Flags ([RwxWin32]::PW_RENDERFULLCONTENT)
            if ($bmp -and -not (Test-BlankBitmap $bmp)) { $captureMethod = 'printwindow' } else { if ($bmp) { $bmp.Dispose() }; $bmp = $null }
        }

        if (-not $bmp -and $Method -ne 'screen') {
            $bmp = Invoke-PrintWindow -Hwnd $w.Hwnd -Flags 0
            if ($bmp -and -not (Test-BlankBitmap $bmp)) { $captureMethod = 'printwindow-legacy' } else { if ($bmp) { $bmp.Dispose() }; $bmp = $null }
        }

        if (-not $bmp -and -not $NoChild -and $Method -ne 'screen') {
            foreach ($child in (Get-ChildWindows -Parent ([IntPtr]$w.Hwnd))) {
                $bmp = Invoke-PrintWindow -Hwnd $child.Hwnd -Flags ([RwxWin32]::PW_RENDERFULLCONTENT)
                if ($bmp -and -not (Test-BlankBitmap $bmp)) { $captureMethod = "child:$($child.Hwnd)"; $capturedHwnd = $child.Hwnd; break }
                if ($bmp) { $bmp.Dispose() }; $bmp = $null
            }
        }

        if (-not $bmp -and -not $NoRaise) {
            $bmp = Invoke-RaiseAndGrab -Window $w -Settle $SettleMs -Foreground:$ForceForeground
            if ($bmp -and -not (Test-BlankBitmap $bmp)) { $captureMethod = 'raise+screen' } else { if ($bmp) { $bmp.Dispose() }; $bmp = $null }
        }
    }

    if ($ClientOnly -and $capturedHwnd -eq $w.Hwnd) {
        # 必须在窗口还处于恢复状态时裁：最小化后 GetClientRect 会变成 0，偏移也不可用了。
        $cropped = Crop-ClientArea -Bitmap $bmp -Hwnd $w.Hwnd
        if (-not [object]::ReferenceEquals($cropped, $bmp)) { $bmp.Dispose(); $bmp = $cropped }
    }
    } finally {
        if ($restored) { [void][RwxWin32]::ShowWindow([IntPtr]$w.Hwnd, 6) }   # SW_MINIMIZE
    }

    if (-not $bmp) {
        $reason = if ($w.Minimized) {
            '窗口已最小化，没有可截图的内容（加 -RestoreIfMinimized 可让工具临时恢复它）'
        } else {
            'PrintWindow 和抓屏都没有拿到内容（窗口可能被 DWM 挂起、在其它虚拟桌面或是独占全屏）'
        }
        [Console]::Error.WriteLine(("失败: 窗口 0x{0:X} ({1})：{2}" -f $w.Hwnd, $w.Title, $reason))
        continue
    }

    $path = if ($Out) {
        if ($targets.Count -gt 1) {
            [System.IO.Path]::Combine([System.IO.Path]::GetDirectoryName($Out),
                ([System.IO.Path]::GetFileNameWithoutExtension($Out) + "_$index" + [System.IO.Path]::GetExtension($Out)))
        } else { $Out }
    } else {
        $base = if ($Name) { ($Name -replace '\.exe$', '') } else {
            $procName = (Get-Process -Id $w.Pid -ErrorAction SilentlyContinue).ProcessName
            if ($procName) { $procName } else { "pid$($w.Pid)" }
        }
        Join-Path $OutDir ("{0}_{1}_{2}.png" -f $base, $stamp, $index)
    }

    try {
        Save-Shot -Bitmap $bmp -Path $path
    } catch {
        [Console]::Error.WriteLine("失败: 无法写入 $path：$($_.Exception.Message)")
        continue
    } finally { $bmp.Dispose() }

    $info = Get-Item $path
    [void]$saved.Add([pscustomobject]@{
        Path   = $info.FullName
        Method = $captureMethod
        Hwnd   = $w.Hwnd
        Title  = $w.Title
    })
    $note = switch ($captureMethod) {
        'printwindow' { '' }
        'printwindow-legacy' { '（老式重绘路径）' }
        'raise+screen' { '（PrintWindow 无内容，已临时置顶抓屏并放回原位）' }
        default { "（子窗口 $captureMethod）" }
    }
    Write-Output ("已保存: {0}{1}" -f $info.FullName, $note)
}

if ($saved.Count -eq 0) { exit 4 }
exit 0
