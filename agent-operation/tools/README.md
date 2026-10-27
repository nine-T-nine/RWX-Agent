# RWX Agent 工具

以下示例从仓库根目录执行。CLI 命令见 [help.md](../help.md)，启动见 [START.md](../START.md)，服务器部署见 [SERVER.md](../SERVER.md)。

| 工具 | 支持环境 | 用途 |
|---|---|---|
| `rwx-cli.py` | Windows、Linux、WSL、macOS，Python 3.9+ | 单次、批量、交互 CLI；推荐入口 |
| `rwx-game-events-to-jsonl.py` | Python 3 | 订阅本地事件并追加 JSONL |
| `rwx-cli.sh` | Bash + Python 3 | 上述 Python 客户端的包装，无 PowerShell 依赖 |
| `rwx-cli.ps1` / `rwx-cli-lib.ps1` | Windows PowerShell 5.1+ / PowerShell 7 | .NET 客户端；读端口文件后可回退到 Windows PID/日志发现 |
| `rwx-cli-daemon.ps1` | Windows PowerShell | 兼容旧的文件邮箱集成；多个调用方不要共享一个邮箱 |
| `screenshot.ps1` / `.bat` | Windows GUI | 窗口截图；不适用于 Headless |
| `experimental_scripts/*.js` | 原宿主接口 | 算法参考，不是当前 CLI 的可执行插件 |

## 发送命令

先启动 RWX：GUI 使用 `./gradlew.bat :desktop:rwxCliRun`；Headless 使用 `./gradlew.bat :desktop:run '--args=--headless'`。Linux/WSL 把启动器换为 `bash ./gradlew`。

```powershell
python agent-operation/tools/rwx-cli.py --list
python agent-operation/tools/rwx-cli.py rwx-game-state --summary
python agent-operation/tools/rwx-cli.py rwx-connect <server-address>:5123 --name AgentA
python agent-operation/tools/rwx-cli.py rwx-connection-status
python agent-operation/tools/rwx-cli.py --batch-file commands.txt
python agent-operation/tools/rwx-cli.py --interactive
```

Linux/WSL 使用 `python3`，或者：

```bash
bash agent-operation/tools/rwx-cli.sh rwx-game-state --summary
bash agent-operation/tools/rwx-cli.sh send 'rwx-game-step --seconds 30' 120
```

PowerShell 原生客户端：

```powershell
.\agent-operation\tools\rwx-cli.ps1 -List
.\agent-operation\tools\rwx-cli.ps1 rwx-game-state --summary
.\agent-operation\tools\rwx-cli.ps1 -Batch 'rwx-game-state --summary','rwx-game-unit-list --owner self --full'
.\agent-operation\tools\rwx-cli.ps1 -BatchFile .\commands.txt
.\agent-operation\tools\rwx-cli.ps1 -Interactive
```

批量文件是 UTF-8，每行一条命令，支持引号，忽略空行与 `#` 开头的行。批量/交互复用一条 TCP 连接，按原版 CLI 的 `!!framed` / RS 协议分隔多行回复。

Python 的客户端选项必须放在命令名前。默认超时 60 秒，长单机 step 可用 `--timeout 120`。不要给多人游戏暂停、加速或 step。

## 端口、实例与断线

顺序：显式 `--port` / `--port-file`，`RWX_CLI_PORT_FILE`，脚本所在仓库的 `agent-operation/tmp/port`，最后是独立实例文件。候选必须通过 `rwx-ping`；异常退出留下的端口不会被直接信任。若主文件暂时缺失且有多个有效实例，必须显式选择。端口文件只有数字，旁边的 `.lock` 和 `.instances/` 用于进程间协调。

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-game-state --summary
$env:RWX_CLI_PORT_FILE = (Resolve-Path agent-operation/tmp/a/port).Path
.\agent-operation\tools\rwx-cli.ps1 rwx-connection-status
```

显式文件失效时直接报错，不回退到另一个 Agent。PowerShell 的 `-Id` / `-ProcessName` 仅在 Windows 上选择进程端口，优先于默认文件；Linux/Headless 推荐端口文件。CLI 绑定回环，不能把游戏服务器的 `5123` 当作 CLI 端口。

WSL NAT 不保证能访问 Windows 回环；在 WSL 中运行 Linux RWX，或通过 `powershell.exe` 调用转换为 Windows 路径的 Windows 客户端。此限制与多人游戏直连无关。

CLI 客户端退出不关闭游戏连接；`rwx-disconnect` 关闭多人连接；`rwx-exit` 退出 RWX。断线后先检查状态，再显式重连，客户端不会盲目重发可能已执行的生产/攻击命令。

Python 退出码：`0` 成功、`2` 发现/连接/参数失败、`4` RWX 回复 `error:`。PowerShell 另使用 `3` 表示连接失败。命令改为 `rwx-*`，不保留旧前缀别名。

## 实时事件 JSONL

启用 RWX CLI 后事件服务自动就绪，日志中的 `RWX_EVENTS_READY` 和 `agent-operation/tmp/events-port` 提供独立端口；端口仅监听 `127.0.0.1`。在任意工作目录运行：

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
python agent-operation/tools/rwx-game-events-to-jsonl.py --port-file agent-operation/tmp/a/events-port --info --allies
```

默认日志固定为脚本上级目录的 `agent-operation/tmp/game-events.jsonl`，逐行追加并 flush；`--output` 可指定其他文件，`--port` 可覆盖发现文件。Ctrl+C 停止。每次启动扫描日志中的最后会话与序列，连接后自动重放；重复序列不追加。默认订阅 `damaged,destroyed,losses,new`，`--info` 增加信息事件，`--allies` 切换友军击杀和损失。

服务端最多保留 180 游戏秒、1000 条传输记录。恢复范围不足或采集到传输的队列溢出会记录 `gap` / `overflow`；进程重启不能恢复旧进程的内存日志。JSON 的 `event`、游戏时间和 tick 是游戏事件数据，`session`、`seq`、`eventId`、`revision` 为传输元数据。`update` 修改已有聚合，`remove` 中 `replaced` 撤销被重新合并的聚合，`expired` 仅表示它退出查询窗口；这些记录不应当作新损失计数。`cursor` 表示被订阅条件过滤的序列；`parse_error` 记录接收器无法解析的消息。接收器不进行事件重要性判断。

多个接收器可以同时订阅，各自按自己的游标回放，互不影响；每个连接使用独立线程，不读取数据的慢客户端只会拖住自己（服务端最多同时服务 16 个订阅，超出时拒绝新连接）。

## 可选 Windows 文件邮箱

```powershell
.\agent-operation\tools\rwx-cli-daemon.ps1 -Start
.\agent-operation\tools\rwx-cli-daemon.ps1 -Status
.\agent-operation\tools\rwx-cli-daemon.ps1 -Stop
```

默认目录由脚本位置确定，为 `agent-operation/tmp/daemon/`，与启动时的当前目录无关。`req.txt`、`resp.txt`、`state.txt`、`pid.txt` 和 `daemon.log` 属于这个 Windows 工具，不是游戏网络端口发现协议。多实例需独立 `-Dir` 并明确 `RWX_CLI_PORT_FILE`。Bash 包装已不依赖此工具；`start` / `status` 仅探测，`stop` 不终止任何 RWX 进程。

## Windows GUI 截图

```powershell
.\agent-operation\tools\screenshot.ps1 -Title 'RWX Game' -NoRaise
.\agent-operation\tools\screenshot.ps1 -Title 'RWX Game' -List
.\agent-operation\tools\screenshot.bat -Id 12345 -ClientOnly -Out .\agent-operation\tmp\now.png
```

默认输出 `agent-operation/tmp/`。工具使用 Win32 捕获；`-NoRaise` 禁止置顶回退，最小化窗口需要显式 `-RestoreIfMinimized`。Headless 没有窗口，应使用状态/单位/地图查询而非截图。
