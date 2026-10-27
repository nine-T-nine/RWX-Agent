# 进行RWX游戏

## 开始前

以下示例均从仓库根目录运行。需要 Java 25；战术参考 [.agents/skills/rwx-cli-tools/strategy.md](../.agents/skills/rwx-cli-tools/strategy.md)。完整命令见 [help.md](help.md)。旧的 `rwx-local-*` 命令已移除。

Windows GUI：`./gradlew.bat :desktop:rwxCliRun`。无 GUI：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --no-steam'
```

Linux / WSL：

```bash
bash ./gradlew :desktop:run --args='--headless --no-steam'
```

启动进程在前台持续运行；需要后台启动时使用自己平台的进程管理器。Headless 没有窗口、图形上下文或额外终端。

CLI 监听成功后打印 `RWX_CLI_TOOL_READY` 并原子写入 `agent-operation/tmp/port`。文件只含数字；工具会先读取它并用 `rwx-ping` 验证，忽略异常退出留下的失效端口。CLI 绑定本机回环地址，端口与多人游戏的 `5123` 不同。

同一进程还启动本机事件流并打印 `RWX_EVENTS_READY`；事件端口保存在 `agent-operation/tmp/events-port`，不占用 CLI 命令端口。读取该文件可查看端口。运行 `python agent-operation/tools/rwx-game-events-to-jsonl.py` 后，事件追加至 `agent-operation/tmp/game-events.jsonl`，按 Ctrl+C 停止接收器。断线会重连并从日志最后的序列恢复；缓存过期、服务重启或队列溢出会以 `gap` / `overflow` 记录。多实例可用 `--port-file` 选择事件端口文件，详见 [工具说明](tools/README.md)。

CLI 在收包入口自动记录完整命令、当前对局分秒及执行者阵营，并将日志和次数统计写入 `evaluate/output/`；统计首部包含每局实际进入时间。文件路径打印在 `RWX_CLI_COMMAND_RECORDING` 行，详见 [命令记录工具](../evaluate/utils/COMMAND_RECORDING.md)。

```powershell
python agent-operation/tools/rwx-cli.py rwx-help 1
python agent-operation/tools/rwx-cli.py rwx-skirmish
python agent-operation/tools/rwx-cli.py rwx-battleroom-select-map --name "Lake (2p)"
python agent-operation/tools/rwx-cli.py rwx-battleroom-settings --income 2 --aiDifficulty impossible
python agent-operation/tools/rwx-cli.py rwx-battleroom-start
```

Linux / WSL 使用 `python3`，或 `bash agent-operation/tools/rwx-cli.sh <命令与参数>`。

## 多人连接与生命周期

真人手动启动 GUI 时不需要 CLI：在“多人游戏 → 原版服务器”选择地址直连，输入 `<server-address>:5123`；也可在启动参数中使用 `--join-server=<server-address>:5123`。这两条路径复用原版连接器，支持可达、版本和模组兼容的服务器。GUI 可以正常截图、键鼠操控；需要 Agent 同时控制时加 `--rwx-cli`。

常驻服务器使用 `rwx-host --managed-room true`：首位客户端是大厅管理员，管理员退出后仅在大厅自动接替；对局中不移交。管理员从 GUI 或 CLI 配置后开局。新身份局中加入自动观战，原玩家重连保留原席位；没有在线非旁观玩家时结束本局、保留连接并返回大厅。云端安装使用 `server/rwx.service`，部署记录见 [DEPLOYMENT.md](DEPLOYMENT.md)。

```powershell
python agent-operation/tools/rwx-cli.py rwx-connect <server-address>:5123 --name AgentA
python agent-operation/tools/rwx-cli.py rwx-connection-status
# joined 表示已经加入房间；in-game 表示游戏已开始并载入。
python agent-operation/tools/rwx-cli.py rwx-game-state --summary
python agent-operation/tools/rwx-cli.py rwx-game-unit-list --owner self --full --compact
python agent-operation/tools/rwx-cli.py rwx-disconnect
# 保留同一进程/身份重连原地址：
python agent-operation/tools/rwx-cli.py rwx-connect --reconnect true
# 退出进程；关闭本地 Python 客户端本身不会断开游戏连接。
python agent-operation/tools/rwx-cli.py rwx-exit
```

普通玩家只能查询可见信息、控制己方单位；开始游戏、修改房间设置由房主执行。多人游戏禁止本地暂停、加速、step 和单机 restart。服务器可在房主明确配置 `rwx-host --auto-start-players 2` 后等待两个非旁观玩家自动开局。

多个 Agent 必须使用独立数据目录保留各自身份，并显式选择各自 CLI 文件。例如启动 A 时使用：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --data-dir=agent-operation/tmp/a --cli-port-file=agent-operation/tmp/a/port'
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connect <server-address>:5123 --name AgentA
```

B 使用 `b` 目录和 `AgentB`。默认共享端口文件由一个主实例持有；其他实例在日志给出的 `instancePortFile` 独立发布，主实例退出后剩余实例接替。不得从多个端口中随便选一个下达命令。

WSL 默认 NAT 下的 Linux 回环地址属于 WSL；建议在 WSL 中启动自己的 Linux RWX 实例。若操纵 Windows RWX，使用 Windows PowerShell 客户端通过 WSL interop 调用，路径先用 `wslpath -w` 转换，不能假定 WSL 的 `127.0.0.1` 一定可达 Windows 回环。

服务器部署见 [SERVER.md](SERVER.md)，工具细节见 [tools/README.md](tools/README.md)。

## 胜利条件

消灭所有敌方阵营可以生产的单位和建筑(包括指挥中心)

## 游戏控制
- 你可以不择手段使用**所有方式**来操控游戏、获取战局信息、取得胜利，不过最好及时花费资金、实时关注战报
- 进行正式对局任务时，**禁止改动源码、禁止使用游戏暂停**；不要随意加速。
以下是tips：
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
