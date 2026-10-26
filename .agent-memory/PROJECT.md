# PROJECT

## 是什么

Rusted Warfare（RW）的桌面/多平台移植与自动化改造版。在保留原版引擎（反编译还原的 Java 代码）和原版多人网络协议的前提下，
提供 Agent 可用的 CLI、Headless 运行、端口自动发现、托管房间与云端部署模板。

## 模块

| 目录 | 内容 |
|---|---|
| `core/` | 引擎与游戏逻辑（Java，反编译还原）+ Kotlin 外壳：`io.github.rwx.session`、`io.github.rwx.app`、`io.github.rwx.ui` |
| `desktop/` | 桌面启动：Slick/LWJGL GUI、`headless/`（HeadlessMain、HeadlessGameSession、HeadlessGraphics）、发行包任务 |
| `ui/` | Compose 菜单/大厅界面（KMP） |
| `slick2d-lwjgl3/` | 图形/输入后端 |
| `mod-api/`、`mods/`、`mod-tools/` | 模组 API 与工具 |
| `android/` | Android 壳 |
| `web/` | RWX-Agent 静态项目网站、三场网页录像、媒体生成与资源校验脚本；GitHub Pages 部署见 `web/README.md` |
| `agent-operation/` | Agent 文档与工具：`START.md`、`help.md`、`tools/rwx-cli.py`、`server/`（systemd、host-service）、`VALIDATION.md` |
| `evaluate/utils/` | 评估工具；`CommandRecorder.java` 随 core 打包，在 CLI 收包时记录完整命令、游戏时间与执行者，导出日志及命令名次数统计到 `evaluate/output/` |
| `.agents/skills/rwx-cli-tools/` | 战术参考与 PowerShell 批处理辅助脚本 |

## 关键实现点

- **CLI**：`RwxCliCommands` 注册命令；`RwxCliTool` 监听本机回环随机端口，成功后打印 `RWX_CLI_TOOL_READY` 并把端口原子写入
  `agent-operation/tmp/port`（主实例 + 每实例记录，退出清理，异常退出可识别失效端口）。命令统一 `rwx-*`，不保留 `rwx-local-*` 别名。
- **命令记录**：`RwxCliCommandCapture` 在 `serveClient` 执行前抓取命令与当前玩家/阵营；通过现有每帧任务队列记录实际进入对局时间，日志使用接收时的游戏时钟。每个 CLI 实例独立输出两个 TXT，详见 `evaluate/utils/COMMAND_RECORDING.md`。
- **Session**：`RwxSessionCommands.kt` 负责命令分发、连接状态、托管房间驱动；`GameSession.kt` 负责房间配置/开局/重开/投降/回大厅。
- **多人**：沿用原版房主调度的确定性锁步；各客户端跑完整模拟。`NetworkEngine` 的 `isProxyController` 表示“代理房主权限”。
- **Headless**：同一个引擎，替换窗口/绘制/音频适配，不依赖 DISPLAY/Wayland；保留 Tick、网络、CLI。
- **托管房间**：`RwxManagedRoom.kt`（core 的 `rwx_cli_tool` 包）实现大厅房主选举、对局中冻结、无人时返回大厅、代理房主请求转发。

## 构建

```powershell
.\gradlew.bat :core:test :desktop:headlessArchive --init-script .\agent-tmp\priority-isolated.gradle `
  --project-cache-dir .\agent-tmp\priority-gradle-cache --max-workers=1 --no-build-cache `
  --no-configuration-cache '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

需要 Java 25。最近完整验证：24 个套件、206 项单测通过（2026-10-26，含命令记录器）；Headless 发行包构建通过。

## 本地联机验证（当前手工流程）

1. Headless 房主（WSL 或本机）：`--headless --no-steam --rwx-cli --cli-port-file=<dir>/port`，然后用 CLI `rwx-host --managed-room true`。
2. Windows 客户端：`launch-network-test.py a|b|c` 用 `--join-server=<host>:5123` 加入；`manual gui manual` 为不带 CLI 的 GUI 客户端。
3. 断言用 `agent-tmp/check-managed-room.py lobby|late` 与 `check-two-rounds.py`（临时脚本，尚未转正）。
