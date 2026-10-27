# RWX Agent 工具

所有示例默认从**仓库根目录**执行，除非另有说明。

相关文档：[命令手册](../help.md) · [游戏启动](../START.md) · [服务器部署](../SERVER.md)

## 1. 工具总览

| 工具 | 环境 | 用途 |
|---|---|---|
| `rwx-cli.py` | Python 3.9+，跨平台 | 通用客户端，支持单次、批量、交互及持久标准流 |
| `rwx-game-events-to-jsonl.py` | Python 3 | 订阅实时事件 Socket，写入 JSONL |
| `rwx-cli.sh` | Bash + Python 3 | Python CLI 的 Bash 包装 |
| `rwx-cli.ps1` / `rwx-cli-lib.ps1` | PowerShell 5.1+ / 7 | Windows 原生 .NET CLI 客户端 |
| `screenshot.ps1` / `.bat` | Windows GUI | 游戏窗口截图，不支持 Headless |
| `experimental_scripts/*.js` | 原宿主接口 | 算法参考，不是当前 CLI 插件 |

## 2. 发送 CLI 命令

实时控制优先使用持久 TCP 连接；若 Agent 不方便直接管理 Socket，则优先使用 Python 持久模式。单次 Python/PowerShell 调用用于通用兼容与调试。

| 方式 | 适用场景 | 主要缺陷 |
|---|---|---|
| 直接持久 TCP | 高频实时控制 | 宿主必须正确管理连接和协议 |
| Python 持久模式 | 支持 stdin/stdout 的 Agent | 需要保留客户端进程 |
| Python 批量模式 | 已确定的连续命令 | 不能自然支持每步观察后决策 |
| Python 单次模式 | 调试、偶发调用 | 重复启动解释器和建立连接 |
| PowerShell 客户端 | Windows 兼容操作 | 单次调用存在进程和连接开销 |
| Bash 包装 | 便捷调用 | 仍依赖底层 Python 客户端 |
| 文件邮箱 | 旧版兼容 | 不作为低延迟实时控制入口；本仓库不再提供邮箱守护进程 |

### 2.1 启动 RWX

**Windows GUI：**

```powershell
.\gradlew.bat :desktop:rwxCliRun
```

**Windows Headless：**

```powershell
.\gradlew.bat :desktop:run '--args=--headless'
```

Linux / WSL 将启动器换为 `bash ./gradlew`。

启动与连接的完整说明见 [START.md](../START.md)。

### 2.2 Python 客户端

支持单次、批量、人用交互和 Agent 持久标准流。`--stdio` 使用同一连接，启动时仅发现和验证一次端口；stderr 输出 `ready` 表示握手完成。stdin 为 UTF-8，每行使用与 `--line` 相同的 shell 引号规则；空行和以 `#` 开头的行忽略，关闭 stdin 正常退出。stdout 每条命令一行 UTF-8 JSON，包含布尔 `error` 和字符串 `reply`，即使回复为空或含换行也有明确边界，并立即 flush。语法错误产生一条 `error` JSON 后继续；服务端 `error:` 也可继续处理，任一错误使最终退出码为 4。连接、握手、超时或截断错误写 stderr 并以 2 退出；在途命令可能已执行，不自动重发。`--interactive` 保留提示符与 `exit` / `quit` / `:q`；不适合机器解析。

```powershell
# 查看可用实例
python agent-operation/tools/rwx-cli.py --list

# 查询游戏状态
python agent-operation/tools/rwx-cli.py rwx-game-state --summary

# 连接多人服务器
python agent-operation/tools/rwx-cli.py rwx-connect <server-address>:5123 --name AgentA

# 查看连接状态
python agent-operation/tools/rwx-cli.py rwx-connection-status

# 批量执行
python agent-operation/tools/rwx-cli.py --batch-file commands.txt

# Agent 持久模式；输入 rwx-ping，读取一行 JSON，再输入下一条
python agent-operation/tools/rwx-cli.py --stdio

# 人工交互模式
python agent-operation/tools/rwx-cli.py --interactive

# 带引号的一行命令
python agent-operation/tools/rwx-cli.py --line 'rwx-battleroom-select-map --name "Lake (2p)"'
```

Linux / WSL 使用 `python3`，也可以通过 Bash 包装脚本执行：

```bash
bash agent-operation/tools/rwx-cli.sh rwx-game-state --summary
bash agent-operation/tools/rwx-cli.sh send 'rwx-game-step --seconds N' X
```

**Windows PowerShell 原生客户端：**

```powershell
.\agent-operation\tools\rwx-cli.ps1 -List
.\agent-operation\tools\rwx-cli.ps1 rwx-game-state --summary
.\agent-operation\tools\rwx-cli.ps1 -Batch 'rwx-game-state --summary','rwx-game-unit-list --owner self --full'
.\agent-operation\tools\rwx-cli.ps1 -BatchFile .\commands.txt
.\agent-operation\tools\rwx-cli.ps1 -Interactive
```

### 2.3 批量、超时与协议

- **批量文件：** UTF-8 编码，每行一条命令，支持引号，忽略空行和 `#` 注释；逐条发送并等待回复，不是原子事务，回执不一定表示游戏效果已应用。
- **连接复用：** 批量与交互模式复用 TCP 连接，使用原 CLI 的 `!!framed` / RS 协议分隔多行回复。
- **参数顺序：** Python 客户端选项必须放在 RWX 命令名前。
- **超时：** 默认 60 秒，长时间单机操作可使用 `--timeout 120`。
- **多人限制：** 禁止本地暂停、加速或 `step`。
- **命令前缀：** 统一为 `rwx-*`

**直接 TCP 协议：** 连接 `127.0.0.1` 的 CLI 端口。标准请求为 UTF-8 `命令名<TAB>参数列表<LF>`，每个参数分别编码为 UTF-8 后 Base64，再以英文逗号连接；无参数仍可发送 `rwx-ping\t\n`。例如 `rwx-game-state --summary` 为 `rwx-game-state\tLS1zdW1tYXJ5\n`。服务端也有人工纯文本回退，但程序应使用上述编码，不要直接发送空格分隔命令。编码表示无法区分唯一的空字符串参数和无参数。

单次请求写入后半关闭写端（`shutdown(SHUT_WR)`），读取直到 EOF；回复为 UTF-8，检查是否以 `error:` 开头。持久连接先发送 `!!framed\n`，读取 `0x1E ready 0x1E`，之后每次发送一条请求并读取完整 `0x1E<UTF-8 回复>0x1E`；服务端会将回复内的 RS 替换为空格。空回复是两个连续 RS，多行回复仍是同一帧。处理 TCP 分片、EOF、超时和 UTF-8 解码错误；不要假定一次 `recv` 就有完整帧。可直接执行的握手、探活和带参示例见 [START.md 第 2.1 节](../START.md#21-执行-cli-命令)。单次探活示例：

```python
import socket
from pathlib import Path
port = int(Path("agent-operation/tmp/port").read_text(encoding="ascii").strip())
with socket.create_connection(("127.0.0.1", port), timeout=3) as s:
    s.settimeout(60)
    s.sendall(b"rwx-ping\t\n")
    s.shutdown(socket.SHUT_WR)
    parts = []
    while True:
        chunk = s.recv(65536)
        if not chunk:
            break
        parts.append(chunk)
    reply = b"".join(parts).decode("utf-8").rstrip("\r\n")
    if reply != "pong":
        raise RuntimeError("endpoint is not live: " + reply)
    print(reply)
```

每条连接内服务端顺序执行和回复，无请求 ID 或异步多路复用；客户端应等待当前帧再发送下一条。不同客户端可同时连接，但服务端只有四个工作线程，空闲持久连接也占用线程，更多连接可能等待；命令对游戏的实际作用仍受引擎调度控制。Socket 往返包含服务端处理时间，Agent 推理和工具调度另计；`game-step --seconds` 是推进的游戏时间，`game-events --seconds` 是过去事件查询窗口，不是推荐决策周期。

在同一实例和相近游戏状态下复测（仅查询，不改变游戏状态）：

```powershell
python agent-operation/tools/benchmark-rwx-cli.py --port-file agent-operation/tmp/port --count 30
python -m unittest discover -s agent-operation/tools -p test_rwx_cli.py -v
```

基准分别测量两种命令，每次轮流使用三种调用方式，输出 P50、P95、最大值；解释器启动/退出、Python 持久进程启动至握手、直接连接另有测量。单次端到端包含端口验证和进程及连接开销；持久调用包含进程管道或 Socket 往返与服务端处理，不能单独推断引擎执行时间。连接/握手及初始化没有被分别注入单次进程测量；结果取决于机器负载和游戏状态，不是性能保证。若状态命令无法在当前状态执行，脚本报错而不记录失败的耗时。

## 3. 端口发现与连接管理

### 3.1 自动发现 CLI 端口

客户端按以下优先级选择：

1. 显式指定的 `--port` / `--port-file`。
2. 环境变量 `RWX_CLI_PORT_FILE`。
3. 仓库内 `agent-operation/tmp/port`。
4. 独立实例的端口文件。

候选端口必须通过 `rwx-ping` 验证，不信任异常退出后遗留的无效端口。

- 显式端口文件失效时直接报错，**不会自动切换其他 Agent**。
- 默认端口文件缺失且存在多个有效实例时，必须显式选择。
- 端口文件仅包含数字；相邻的 `.lock`、`.instances/` 用于进程协调。
- CLI 仅监听回环地址，**多人服务器的 `5123` 不是 CLI 端口**。

Windows PowerShell 额外支持 `-Id` / `-ProcessName` 选择进程端口，并可回退到 PID / 日志发现；进程选择优先于默认文件。Linux / Headless 推荐直接使用端口文件。

### 3.2 指定 Agent 实例

Python：

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-game-state --summary
```

PowerShell 环境变量：

```powershell
$env:RWX_CLI_PORT_FILE = (Resolve-Path agent-operation/tmp/a/port).Path
.\agent-operation\tools\rwx-cli.ps1 rwx-connection-status
```

多 Agent 应分别指定各自的端口文件，避免误操作其他实例。

### 3.3 断线与退出

| 操作 | 效果 |
|---|---|
| 关闭 Python CLI 客户端 | 不影响游戏连接 |
| `rwx-disconnect` | 断开当前多人连接 |
| `rwx-connect --reconnect true` | 显式重连 |
| `rwx-exit` | 退出 RWX 进程 |

断线后应先查询状态，再决定是否重连或重新下令。客户端不会盲目重发可能已经执行的生产、攻击等命令。

**退出码：**

| 退出码 | Python | PowerShell |
|---|---|---|
| `0` | 成功 | 成功 |
| `2` | 发现、连接或参数失败 | — |
| `3` | — | 连接失败 |
| `4` | RWX 返回 `error:` | — |

### 3.4 WSL 注意事项

WSL NAT 模式下，不保证 Linux 回环地址能够访问 Windows 回环地址。

建议在 WSL 内运行 Linux RWX；如需控制 Windows RWX，可使用 `powershell.exe` 调用 Windows 客户端，并通过 `wslpath -w` 转换路径。

该限制与多人游戏服务器直连无关。

## 4. 实时事件 JSONL

### 4.1 启动接收器

RWX CLI 启动后，会额外开启一个**独立的本地事件 Socket**：

| 项目 | 内容 |
|---|---|
| 监听地址 | `127.0.0.1` |
| 就绪日志 | `RWX_EVENTS_READY` |
| 端口文件 | `agent-operation/tmp/events-port` |
| 默认输出 | `agent-operation/tmp/game-events.jsonl` |
| 文件上限 | 最新 500 条记录 |

事件 Socket 不占用 CLI 命令端口。

**默认订阅：**

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
```

**指定实例并增加事件类型：**

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py --port-file agent-operation/tmp/a/events-port --info --allies
```

接收器可以在任意工作目录启动，默认输出位置由脚本自身路径确定。

常用参数：

| 参数 | 作用 |
|---|---|
| `--port-file` | 指定事件端口文件 |
| `--port` | 直接指定事件端口 |
| `--output` | 自定义 JSONL 输出文件 |
| `--info` | 增加信息事件 |
| `--allies` | 切换友军击杀和损失 |

默认订阅事件：`damaged,destroyed,losses,new`。

按 `Ctrl+C` 停止接收器。接收器只负责事件接收、转换和存储，**不判断事件重要性**。

### 4.2 日志与断线恢复

接收到的协议消息逐条转换为 JSONL，追加写入并立即 `flush`。

**接收器本地日志：**

- 最多保存最新 500 条记录。
- 超出上限后，重写文件并保留尾部 500 条。
- 重启时扫描日志中的最后会话及序列号，自动请求续传。
- 已存在的重复序列不会再次追加。

**游戏端缓存：**

- 最多保留 180 游戏秒、1000 条传输记录。
- 每个接收器使用独立游标，互不影响。
- 超出服务端缓存范围的历史记录无法从当前缓存恢复。
- RWX 进程重启后，旧进程的内存事件日志不再可用。

断线自动重连；缓存过期、会话重启或队列溢出可能造成记录缺失，并通过 `gap` / `overflow` 显式标记。

### 4.3 事件协议说明

| 字段 / 类型 | 含义 |
|---|---|
| `event` | 游戏事件数据 |
| 游戏时间、`tick` | 事件发生的游戏时间 |
| `session` | 当前会话标识 |
| `seq` | 传输序列号 |
| `eventId` | 事件标识 |
| `revision` | 聚合版本 |
| `update` | 更新已有聚合事件 |
| `remove: replaced` | 撤销被重新合并的聚合 |
| `remove: expired` | 聚合退出查询窗口 |
| `cursor` | 被订阅条件过滤的序列位置 |
| `gap` | 事件历史无法完整恢复 |
| `overflow` | 事件传输队列溢出 |
| `parse_error` | 接收器无法解析协议消息 |

**注意：** `update`、`remove` 是聚合维护操作，不能重复统计为新损失；`expired` 也不代表单位在该时刻被摧毁。

### 4.4 多接收器

事件 Socket 允许多个客户端独立订阅：

- 每个连接拥有独立的线程和恢复游标。
- 慢客户端不会阻塞其他接收器。
- 最多同时服务 16 个订阅。
- 超出连接数量限制时拒绝新连接。

## 5. 可选工具

### 5.1 Windows GUI 截图

```powershell
# 截取指定窗口，不主动置顶
.\agent-operation\tools\screenshot.ps1 -Title 'RWX Game' -NoRaise

# 列出匹配窗口
.\agent-operation\tools\screenshot.ps1 -Title 'RWX Game' -List

# 按窗口 ID 截取客户端区域
.\agent-operation\tools\screenshot.bat -Id 12345 -ClientOnly -Out .\agent-operation\tmp\now.png
```

默认输出目录：`agent-operation/tmp/`。

工具通过 Win32 捕获窗口：

- `-NoRaise`：禁止置顶回退。
- `-RestoreIfMinimized`：显式恢复最小化窗口后截图。
- Headless 无窗口，不能截图，应改用状态、单位或地图查询。

---

相关文档：[命令手册](../help.md) · [游戏启动](../START.md) · [服务器部署](../SERVER.md)
