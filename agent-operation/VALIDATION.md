# RWX 远程连接与 Headless 验证记录

日期：2026-10-25。云服务器的部署与公网验证记录见 [DEPLOYMENT.md](DEPLOYMENT.md)；本文记录本机与 WSL 的改造验证。

## 环境与结论

- Windows：Java 25、Python、Windows PowerShell 5.1 / PowerShell 7；GUI 使用现有 Slick 后端。
- Linux：Ubuntu 22.04 WSL2，Temurin 25 JRE。发行包在 `/tmp/rwx-runtime-validation-*/` 解压，工作目录和数据目录均位于该目录，脱离源码仓库运行。
- 游戏：原版网络版本 176，地图 `Lake (2p)`，旁观房主、两个独立 Agent，收入 2、初始单位 2、无迷雾。关闭迷雾用于对照两个客户端观察到的单位位置。
- 本次为短时功能验证，没有进行长期负载、多人压力或所有模组兼容性测试。

| 验证项 | 结果与边界 |
|---|---|
| 命令重命名 | 注册表测试覆盖全部命令；真实 CLI 拒绝 `rwx-local-game-state`，不保留别名 |
| Windows GUI | 创建 `RWX Game` 主窗口，加入房间、开局、查询及重连通过 |
| Windows Headless | 主窗口句柄为 0，真实引擎、CLI、网络及单位控制运行 |
| Ubuntu Headless 发行包 | 无 DISPLAY / WAYLAND_DISPLAY；没有启动 Xvfb；进程映射未加载检查到的 GL/EGL/X11、LWJGL、OpenAL 库 |
| 本机直连 | Windows Headless 房主与客户端通过 `127.0.0.1:5123` 完成注册；非房主修改设置被拒绝 |
| 跨系统直连 | Windows GUI、Windows Headless 同时加入 WSL 私网接口 `<wsl-address>:5123`，取得 team 0、team 1 |
| 自动开局 | `--auto-start-players 2` 等到两个非旁观真人客户端后走原版开局流程 |
| 密码 | 错误密码返回 `Password rejected`；随后使用正确密码加入 |
| 单位命令同步 | A 的重型坦克从 x=940 移动至约 x=800，B 同时观察到移动；通过原版命令队列提交，`appliedImmediately: false` |
| 控制权限 | B 控制 A 的单位返回 `Unit is not controllable`；普通玩家修改收入、添加 AI 被拒绝 |
| 多人时间控制 | `pause`、`speed`、`step`、单机 `restart` 均拒绝；拒绝 `step` 不再触发单机暂停兜底 |
| 重连 | GUI 和 Headless 分别恢复原 team 并继续 Tick；原版保存状态、quick resync 路径执行 |
| 超时与断线 | 静默 TCP 对端注册超时后 socket 关闭、CLI 继续返回 pong；房主断开后客户端显示 `Game server connection closed` |
| 同步校验 | 短时运行中执行原版 checksum；未记录 checksum mismatch / desync。重连触发的 quick resync 属于预期流程 |
| CLI 文件 | 真实独立进程验证数字文件、重启替换、拒绝失效端口、次实例不覆盖、主实例崩溃后接替、正常退出清理 |
| CLI 客户端 | Python 单次请求、Windows PowerShell 5.1 单次/批量 framed、WSL Bash 包装实际通信通过 |
| 默认发现路径 | GUI 自动写入默认 `agent-operation/tmp/port`；Python / PowerShell 从其他工作目录读取并通信通过 |
| 单机兼容 | Headless 单机暂停、推进 1 秒、同图重开并保留收入、投降后 outcome=defeat 通过 |
| 托管脚本 | 在实际 Linux 发行包中执行 `host-service.py`，成功重新托管并等待客户端；不等于 systemd 云端实测 |
| systemd 模板 | WSL `systemd-analyze verify` 检查通过；未在目标服务器安装/启动服务 |
| 物理局域网 / 云公网 | 未验证。WSL 私网接口验证不代替独立物理主机、防火墙和云安全组验收 |
| UDP | 托管复用原版可靠 UDP 监听；本次 Agent 连接使用 TCP，没有验证 UDP 游戏流量 |

最终构建的真实数据示例：GUI 重连前 team 1 / tick 574，重连后 team 1 / tick 593；Headless 重连前 team 0 / tick 664，重连后 team 0 / tick 684。前一轮对局中两个客户端推进至约 tick 1744 / 1742。查询不是同一时刻采样，少量 Tick 和坐标差不能直接判为不同步，应结合原版校验结果。

## RELAY-CN 的实际结果

使用 `rwx-connect RELAY-CN --name RWX-Probe --timeout-ms 20000`，通过原版 `MasterServerClient` 动态获取列表，发现发布者标签 `RELAY-CN 入口`（探测到的地址已脱敏）。代码没有硬编码地址。

TCP 建连成功，原版预注册/注册流程执行；入口下发踢出包，原文为：

```text
Your client is newer then the server
```

最终构建的 CLI 状态保留此原因：`state: error`、`error: Kicked: Your client is newer then the server`。

因此，**已实现中继地址解析、发现和原版连接流程，但本次 RELAY-CN 未成功加入房间，更没有完成中继开局或对战验证**。列表中宣称的版本号不能替代实际握手。没有修改版本号或绕过版本/单位校验以强行加入；使用者需要当前可接受本项目版本的中继入口和有效房间码。

自建 RWX 房主的完整对局路径已经通过上述 Windows / WSL 验证；云服务器仍需用户按 [SERVER.md](SERVER.md) 部署后验收。

## 自动化检查

```powershell
.\gradlew.bat :core:test :desktop:headlessArchive --max-workers=1
```

本机 IDE 同时使用常规 Gradle 输出目录，因此本次实际构建将输出及 Gradle 项目缓存隔离到 `agent-tmp/`，关闭构建缓存和 Kotlin 增量编译，避免其他进程干扰。完整测试为 **21 个测试套件、195 个测试，0 failure / 0 error**。

新增/扩展回归覆盖：

- `RwxCliDiscoveryTest`：全部规范命令、拒绝旧前缀、数字文件、多实例保护、接替和失效记录替换。
- `LocalSkirmishLifecycleTest`：多人 `step` 被拒绝时速度/暂停/Tick 不受影响；原版断开包发送后，静默对端的 socket 也能释放。
- 原有 CLI、自动化、单位查询及单机生命周期测试继续执行。

其他检查：Python 编译；PowerShell 5.1 / 7 语法解析；Bash `-n`；Markdown 文件链接；本任务文件的 `git diff --check`。工作区同时存在用户新增的评测文件，未为本任务改写其内容。

发行包任务生成 `desktop/build/distributions/rwx-headless.tar.gz`。本次隔离构建的实际产物在 `agent-tmp/priority-build/RWX_desktop/distributions/rwx-headless.tar.gz`，展开目录为相邻的 `headless/`。包含引擎资源、依赖 JAR、启动器、CLI 和 systemd 材料。

## 复现主要对局验证

先构建发行包。在三个独立终端/进程分别启动以下实例；Java 25 可执行文件必须位于 PATH，或替换为实际路径。Linux 将 `python` 换为 `python3`。

```text
java -Drwx.cli.terminal=false -cp "desktop/build/headless/lib/*" io.github.rwx.DesktopMain --headless --no-steam --data-dir=agent-operation/tmp/host --cli-port-file=agent-operation/tmp/host/port
java -Drwx.cli.terminal=false -cp "desktop/build/headless/lib/*" io.github.rwx.DesktopMain --headless --no-steam --data-dir=agent-operation/tmp/a --cli-port-file=agent-operation/tmp/a/port
java -Drwx.cli.terminal=false -cp "desktop/build/headless/lib/*" io.github.rwx.DesktopMain --rwx-cli --no-steam --data-dir=agent-operation/tmp/b --cli-port-file=agent-operation/tmp/b/port
```

第三条为 GUI；无桌面的 Linux 改为 `--headless`。跨系统 GUI 包应在目标平台构建，Headless 发行包不加载图形本机库。

房主 CLI：

```text
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/host/port rwx-host --port 5123 --map "Lake (2p)" --spectator true --auto-start-players 2
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/host/port rwx-battleroom-settings --fog none --income 2 --startUnits 2
```

两个 Agent：

```text
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connect 127.0.0.1:5123 --name AgentA
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/b/port rwx-connect 127.0.0.1:5123 --name AgentB
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connection-status
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/b/port rwx-connection-status
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-game-state --summary --json
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-game-unit-list --owner self --full --compact
```

取得 A 的实际单位 ID 后，由 A 提交地图内移动坐标，再由 B 查询相同可见单位的坐标；由 B 尝试控制该 ID，应被拒绝。不要照抄本次日志中的 ID。重连使用：

```text
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-disconnect
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connect --reconnect true
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connection-status
```

跨主机时只将游戏地址改为房主可达 IP；CLI 仍连各自本地回环端口。结束测试后，对每个实例执行 `rwx-exit`，检查对应端口文件删除。

## 主要修改位置

| 范围 | 文件 |
|---|---|
| 命令注册与端口发布 | `RwxCliCommands.java`、`RwxCliTool.java`、新增 `RwxCliPortFile.java` |
| Session 与线程调度 | 新增 `RwxSessionCommands.kt`；`App.kt`、`GameSession.kt`、`BattleRoomLaunchController.kt` |
| 原版网络生命周期 | `NetworkEngine.java`、`NetworkCallbacks.java`、`SendWorker.java` |
| Headless | 新增 `desktop/.../headless/HeadlessMain.kt`、`HeadlessGameSession.kt`、`HeadlessGraphics.kt`；桌面启动/DI/平台桥及 `PlayerTeam.java` |
| 权限 | `RwxLocalBattleRoomCommands.java`、`RwxEngineLocalGameService.java` |
| 工具与部署 | `agent-operation/tools/`；新增 `agent-operation/server/`；两处 `.agents/skills/rwx-cli-tools` PowerShell 辅助脚本 |
| 打包、文档与测试 | core/desktop Gradle、`.gitignore`、根 `START.md`、Agent START/help/tools README、SERVER/VALIDATION、协议文档及相关 CLI 测试 |

## 运行中发现并修复的问题

1. 大厅提前调用对局锁步调度，消耗 Headless 客户端的一次性 ready 通知，导致 tick 0 等待。大厅只处理网络消息，对局载入后才调度模拟。
2. GUI 重连时开局通知早于房间快照发布。使用原版开局包已提供的地图路径，并保留可重放的延迟开局事件。
3. `step` 把多人权限拒绝误判为游戏线程停滞，进入本地强制暂停。现在在兜底流程前拒绝，并保护所有暂停兜底不写多人速度。
4. 原版仅中断阻塞读取线程，静默对端可能导致 socket 不释放。发送原版断开包后关闭本地 socket，结束阻塞读取。
5. PowerShell 5.1 将无 BOM 的中文 UTF-8 脚本按系统代码页解释。工具统一保留 UTF-8 BOM，并实测 5.1 单次/批量请求。
6. 中继踢出原因原先只进入 GUI 提示。增加独立拒绝通知回调，供 CLI 保留原文，避免把旧断开回调中的玩家姓名误当错误原因。
