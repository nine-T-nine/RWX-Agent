# 进行 RWX 游戏

所有命令均从**仓库根目录**运行，要求 Java 25。

- [战术指南](../.agents/skills/rwx-cli-tools/strategy.md)
- [完整命令手册](help.md)
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

使用 `agent-operation/tools/rwx-cli.py` 执行命令。

以下为创建单机对局的完整示例：

```powershell
python agent-operation/tools/rwx-cli.py rwx-help 1
python agent-operation/tools/rwx-cli.py rwx-skirmish
python agent-operation/tools/rwx-cli.py rwx-battleroom-select-map --name "Lake (2p)"
python agent-operation/tools/rwx-cli.py rwx-battleroom-settings --income 2 --aiDifficulty impossible
python agent-operation/tools/rwx-cli.py rwx-battleroom-start
```

Linux / WSL 将 `python` 替换为 `python3`，也可以使用：

```bash
bash agent-operation/tools/rwx-cli.sh <命令与参数>
```

### 2.2 接收游戏事件

启动事件接收器：

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
```

事件持续追加到：

`agent-operation/tmp/game-events.jsonl`

- 按 `Ctrl+C` 停止接收器。
- 断线后自动重连，并根据日志末尾的序列号恢复。
- 缓存过期、服务重启或队列溢出时，通过 `gap` / `overflow` 记录数据缺失。
- 多实例可使用 `--port-file` 指定不同事件端口文件。

详见 [工具说明](tools/README.md)。


## 3. 多人游戏连接

### 3.1 GUI 连接

真人玩家可以直接通过 GUI 连接，无需启用 CLI。

在 **多人游戏 → 原版服务器** 中选择地址直连，输入：

`<server-address>:5123`

也可以使用启动参数：

`--join-server=<server-address>:5123`

两种方式均复用原版连接器，要求服务器可达、游戏版本和模组兼容。

GUI 支持正常截图和键鼠操作；如需 Agent 同时控制，添加 `--rwx-cli`。

### 3.2 Agent 连接

以下命令使用 `rwx-cli.py`，均从仓库根目录执行。

**连接服务器：**

```powershell
python agent-operation/tools/rwx-cli.py rwx-connect <server-address>:5123 --name AgentA
```

**查询连接状态：**

```powershell
python agent-operation/tools/rwx-cli.py rwx-connection-status
```

- `joined`：已加入房间。
- `in-game`：游戏已开始并载入。

**查询对局信息：**

```powershell
python agent-operation/tools/rwx-cli.py rwx-game-state --summary
python agent-operation/tools/rwx-cli.py rwx-game-unit-list --owner self --full --compact
```

**断开连接：**

```powershell
python agent-operation/tools/rwx-cli.py rwx-disconnect
```

**使用同一进程和身份重连原服务器：**

```powershell
python agent-operation/tools/rwx-cli.py rwx-connect --reconnect true
```

**退出 RWX 进程：**

```powershell
python agent-operation/tools/rwx-cli.py rwx-exit
```

注意：关闭本地 Python CLI 客户端，不等于断开游戏连接。

### 3.3 多人模式限制

普通玩家只能读取其可见信息、控制己方单位。房间设置修改及开始游戏由房主负责。

多人模式禁止：

- 本地暂停和加速。
- 本地 `step`。
- 单机 `restart`。

## 4. 托管服务器与对局生命周期

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

## 5. 多 Agent 并行运行

每个 Agent 必须使用**独立数据目录和 CLI 端口文件**，以隔离身份和连接状态。

例如，启动 AgentA 对应的 RWX 实例：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --data-dir=agent-operation/tmp/a --cli-port-file=agent-operation/tmp/a/port'
```

连接服务器：

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connect <server-address>:5123 --name AgentA
```

AgentB 使用 `agent-operation/tmp/b` 和 `AgentB`，其他操作相同。

### 多实例端口规则

- 默认共享端口文件由一个主实例持有。
- 其他实例通过日志中的 `instancePortFile` 发布独立端口文件。
- 主实例退出后，剩余实例自动接替。
- 必须明确指定目标实例的端口文件，**不得随意选择端口下达命令**。

## 6. WSL 网络注意事项

WSL 默认 NAT 模式下，Linux 与 Windows 的回环地址不一定互通。

推荐直接在 WSL 内运行 Linux 版 RWX。

如果需要从 WSL 控制 Windows 版 RWX：

1. 使用 WSL interop 调用 Windows PowerShell 客户端。
2. 通过 `wslpath -w` 转换文件路径。
3. 不要假定 WSL 的 `127.0.0.1` 可以直接访问 Windows 回环地址。

## 7. 游戏对局

胜利条件：**消灭所有敌方阵营可以生产的单位和建筑(包括指挥中心)**
要求:
- 你可以不择手段使用**所有方式**来操控游戏、获取战局信息、取得胜利，不过最好及时花费资金、实时关注战报
- 进行正式对局任务时，**禁止改动源码、禁止使用游戏暂停**；不要随意加速。
tips:
### tip1：使用rwx_cli_tool
- 支持几乎所有的底层命令，提供最大自由度的操控空间,也支持部分**自动化/智能微操**(参见'automation'类指令)，减少管理负担
- 游戏启动后，通过本地 CLI 端口发送命令，详情参见 [help.md](help.md)。

- `rwx-help`：显示完整help.md。
- `rwx-help <标题编号>...`：显示指定标题及其全部子标题下的内容，包含标题本身。如 `rwx-help 5.5` 只显示 5.5，`rwx-help 5` 显示整个第 5 章。可选择多个编号，按文档顺序输出，重叠内容只显示一次。
- `rwx-help [--session|--observe|--combat|--develop|--automation|--history|--reference|--system]`：保留原来的 8 个大模块筛选，英文名称仅用于这些 h2 标题，可组合选择多个模块。编号筛选与英文模块筛选互斥；传入编号时只按编号筛选，例如 `rwx-help --combat 5.5` 只显示 5.5。
- `rwx-help --list [depth]`：只列出标题及编号，每行一个，按深度优先顺序输出，子标题缩进。`depth` 为正整数，默认 1（只列 h2）；2 包含 h3，3 包含 h4，以此类推，无最大深度限制。可与筛选组合，如 `rwx-help 5 --list 2`；深度始终从 h2 算起。

### tip2：直接截图观察战局
已经有封装好的截图工具，在GUI窗口打开的情况下，无视遮挡直接截图到'agent-operation\tmp'目录下。
- 快速使用：'agent-operation\tools\screenshot.ps1 -Title "RWX Game"'
- 详情参见 'agent-operation\tools\README.md' 
注意，想要获得有用的画面信息，你要手动移动窗口到你想要的地方（参见‘本地对局’->'视野'相关命令：'rwx-game-camera' ），游戏开始时的缩放比例为1，推荐不大于1.
### tip3：写辅助脚本
允许在'agent-operation\agent-tmp'目录下维护你的脚本工具，但注意关注游戏时间、特定事件触发回调/唤醒，以免过度等待或者死锁
### tip4：安排子智能体辅助管理
略
### 其他方式
略
 