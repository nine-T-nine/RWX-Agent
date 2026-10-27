# 进行 RWX 游戏

系统终端示例均从**仓库根目录**运行，要求 Java 25。游戏使用本地 TCP Socket 接收 `rwx-*` 命令。

整局复用持久连接：宿主能保持子进程和标准流时，优先使用现有 Python `--stdio`；宿主已支持持久 TCP 时也可直接使用。两者都不可用时使用单次客户端，预定的连续命令可用批量模式。无需自行开发通信工具。

- [战术指南](../.agents/skills/rwx-cli-tools/strategy.md)
- [中文命令手册](help_zh.md) · [英文命令手册 / rwx-help](help.md)
- [工具说明](tools/README.md)
- [服务器部署](SERVER.md)

## 1. 启动游戏

**Windows GUI：**

```powershell
.\gradlew.bat :desktop:rwxCliRun
```

**Windows Headless（无界面）：**

```powershell
.\gradlew.bat :desktop:run '--args=--headless --no-steam'
```

**Linux / WSL Headless：**

```bash
bash ./gradlew :desktop:run --args='--headless --no-steam'
```

启动后，游戏进程会持续占用当前终端。后台运行需使用系统进程管理工具。Headless 模式不创建游戏窗口、图形上下文或额外终端。

### 服务就绪标志

启动成功后，会自动提供以下本地服务：

| 服务 | 就绪日志 | 端口文件 |
|---|---|---|
| CLI 命令接口 | `RWX_CLI_TOOL_READY` | `agent-operation/tmp/port` |
| 游戏事件流 | `RWX_EVENTS_READY` | `agent-operation/tmp/events-port` |

两个服务使用独立端口，均不占用多人游戏的 `5123` 端口。

CLI 仅监听本机回环地址。端口文件通过原子写入更新，内容只有端口数字。CLI 工具会自动读取端口文件，并通过 `rwx-ping` 验证，忽略异常退出遗留的无效端口。

## 2. CLI 命令与事件流

### 2.1 执行 CLI 命令

命令端口默认写在 `agent-operation/tmp/port`，仅监听本机；它不是多人游戏的 `5123` 端口，也不是事件端口。客户端会用 `rwx-ping` 验证端口。多实例请显式选择端口文件（第 5 节）。

**持久通信流程：**在另一个终端启动以下客户端，或由 Agent 宿主启动并在整局保留子进程及其 stdin、stdout、stderr：

```powershell
python agent-operation/tools/rwx-cli.py --stdio
```

Linux / WSL 使用 `python3`。

1. 等待 stderr 输出 `ready`，表示端口验证和连接完成。
2. 向 stdin 写入 `rwx-ping` 并换行，读取 stdout 的一行 JSON；`reply` 应为 `pong`，`error` 应为 `false`。
3. 向同一 stdin 写入 `rwx-game-state --summary` 并换行，读取下一行 JSON；查看 `reply` 和 `error`。尚未开始对局时，状态查询可能返回错误；按 3.2 节开局后再查询。每条命令都等对应回复后再发送下一条。
4. 完成后关闭 stdin。**跨工具调用复用要求宿主保持该进程及标准流**；仅启动一次命令本身不能保证复用。诊断输出在 stderr，多行回复保存在 JSON 的 `reply` 中。

普通终端可按上述步骤逐行输入；不支持保留子进程的宿主可使用单次调用：

```powershell
python agent-operation/tools/rwx-cli.py rwx-ping
```

如果宿主已能可靠管理持久 TCP，可直接连接；协议示例见[工具说明](tools/README.md#23-批量超时与协议)。超时或断线后先重新连接并查询状态，不要盲目重发可能已执行的命令。成功回执也不一定表示游戏效果已应用。

**时间：**Socket 往返包含传输和命令处理或等待；Agent 推理与工具调度另计。`rwx-game-step --seconds` 主动推进单机游戏时间；`rwx-game-events --seconds` 查询过去事件的窗口，不是等待时间或推荐决策周期。

### 2.2 接收游戏事件

启动事件接收器：

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
```

事件持续追加到：

`agent-operation/tmp/game-events.jsonl`

- 只记录 `kind` 为 `update` 的消息（接收时二次过滤）；`event`、`remove`、`cursor`、`hello` 都不入文件。
- 按 `Ctrl+C` 停止接收器。
- 断线后自动重连，并根据日志末尾的序列号恢复（游标跟住所有序列号）。
- 缓存过期、服务重启或队列溢出时，缺失只在 stderr 以 `gap` / `overflow` 报告。
- 多实例可使用 `--port-file` 指定不同事件端口文件。

详见 [工具说明](tools/README.md)。

## 3. 游戏对局

目标：消灭所有敌方阵营能生产的单位和建筑，包括指挥中心。可使用各种方式操控游戏和获取信息，但必须遵守对局权限和以下限制。

- 正式对局禁止修改源码、禁止暂停；不要随意加速。多人限制见 4.3 节。
- 建议及时花费资金、关注战报。查询游戏状态与监听事件有助于掌握战局，见第 2 节。

### tip1：使用 rwx_cli_tool

通过 CLI 操作；自动补产和微操等命令见[命令手册](help_zh.md)。`rwx-help` 显示英文手册，`rwx-help --list 2` 列出标题，`rwx-help 5` 查看第 5 章。

### tip2：直接截图观察战局

GUI 窗口使用[截图工具](tools/README.md#51-windows-gui-截图)；Headless 无法截图。可用 `rwx-game-camera` 移动游戏视野；初始缩放为 1，建议不大于 1，移动GUI界面的镜头参见[命令手册](help.md)第2.4节。此方法偏慢且会有延迟，请在合适时机再使用.如要获取部分地图地形信息，参见[命令手册](help.md)第2.3节。

### tip3：写辅助脚本

在仓库根目录的 `agent-tmp/` 维护临时脚本；注意游戏时间与事件触发，避免过度等待或死锁。

### tip4：安排子智能体辅助管理

你自行安排子智能体辅助你管理。例如安排子智能体监控实时事件输出的jsonl文件并智能判断是否要通知你，你也将自己的决策同步给他。

### 其他方式

遵守上述限制，按需使用其他操作方式。

## 4. 多人游戏连接

### 4.1 GUI 连接

真人玩家可以直接通过 GUI 连接，无需启用 CLI。

在 **多人游戏 → 原版服务器** 中选择地址直连，输入：

`<server-address>:5123`

也可以使用启动参数：

`--join-server=<server-address>:5123`

两种方式均复用原版连接器，要求服务器可达、游戏版本和模组兼容。

GUI 支持正常截图和键鼠操作；如需 Agent 同时控制，添加 `--rwx-cli`。

### 4.2 Agent 连接

以下是 **RWX CLI 命令，不是 Shell 命令**。通过 2.1 节的连接逐条发送并读取回复；单次模式须在每条前加客户端调用。

```text
rwx-connect <server-address>:5123 --name AgentA
rwx-connection-status
rwx-game-state --summary
rwx-game-unit-list --owner self --full --compact
```

把地址占位符替换为实际服务器地址。`rwx-connect` 的回执仅表示连接请求已提交；查询状态，`joined` 表示已加入房间，`in-game` 表示已开始并载入。房主负责开局。

本地单机开局也通过同一连接逐条发送：

```text
rwx-skirmish
rwx-battleroom-show
```

检查地图、对手阵营和出生点；战役室可能已有 AI。需要添加 AI 或调整房间时，先确认地图有可用出生点，命令见[命令手册](help_zh.md)。确认后逐条发送：

```text
rwx-battleroom-start
rwx-game-state --summary
```

开局回执表示已请求开始；若尚未载入，稍后再查询状态。

`rwx-disconnect` 断开多人连接；`rwx-connect --reconnect true` 使用同一进程和身份重连；`rwx-exit` 退出 RWX。关闭 CLI 客户端不会断开游戏连接。

### 4.3 多人模式限制

普通玩家只能读取其可见信息、控制己方单位。房间设置修改及开始游戏由房主负责。

多人模式禁止：

- 本地暂停和加速。
- 本地 `step`。
- 单机 `restart`。

## 5. 托管服务器与对局生命周期

常驻服务器使用：

`rwx-host --managed-room true`

### 房间管理员

- 首位连接的客户端成为大厅管理员。
- 管理员可以通过 GUI 或 CLI 配置房间并开局。
- 管理员退出后，仅在大厅阶段自动移交权限；对局进行中不移交。

### 玩家加入与离开

- 新身份在对局进行中加入时，自动成为旁观者。
- 原玩家重连时保留原有席位。
- 当没有在线的非旁观玩家时，结束当前对局，保留连接并返回大厅。

### 自动开局

房主可以明确配置：

`rwx-host --auto-start-players 2`

服务器将在两名非旁观玩家就绪后自动开局。

云端服务文件：`server/rwx.service`

部署详见 [SERVER.md](SERVER.md)。

## 6. 多 Agent 并行运行

每个 Agent 必须使用**独立数据目录和 CLI 端口文件**，以隔离身份和连接状态。

例如，启动 AgentA 对应的 RWX 实例：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --data-dir=agent-operation/tmp/a --cli-port-file=agent-operation/tmp/a/port'
```

连接该实例的持久客户端：

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port --stdio
```

按 2.1 节探活，再通过该连接发送 4.2 节的命令。

AgentB 使用 `agent-operation/tmp/b` 和 `AgentB`，其他操作相同。

### 多实例端口规则

- 默认共享端口文件由一个主实例持有。
- 其他实例通过日志中的 `instancePortFile` 发布独立端口文件。
- 主实例退出后，剩余实例自动接替。
- 必须明确指定目标实例的端口文件，**不得随意选择端口下达命令**。

## 7. WSL 网络注意事项

WSL 默认 NAT 模式下，Linux 与 Windows 的回环地址不一定互通。

推荐直接在 WSL 内运行 Linux 版 RWX。

如果需要从 WSL 控制 Windows 版 RWX：

1. 使用 WSL interop 调用 Windows PowerShell 客户端。
2. 通过 `wslpath -w` 转换文件路径。
3. 不要假定 WSL 的 `127.0.0.1` 可以直接访问 Windows 回环地址。


