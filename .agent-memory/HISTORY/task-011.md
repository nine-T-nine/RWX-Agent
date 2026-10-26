# task-011（2026-10-26）

## 目标

从实际 CLI 监听端口抓取每条完整命令，记录当前对局分秒和执行者阵营；不同参数按同一个命令名计数，向 `evaluate/output/` 导出完整日志和统计文件，统计首部附每局实际进入时间。

## 改动

- 新增 `evaluate/utils/CommandRecorder.java`：线程安全的逐条 UTF-8 TXT 日志、按命令名及执行者统计、每秒原子刷新统计、`export()` 和退出时最终导出。每实例单独生成文件，参数不参与分类。
- 新增自定义 CLI 桥接 `RwxCliCommandCapture`，在 `RwxCliTool.serveClient` 收到完整命令后、执行前取游戏时钟及控制玩家，支持纯文本、base64 argv、UTF-8/GBK、持久连接及非法命令；不计传输握手。
- 日志格式为 `[分:秒]<name=玩家;player=槽位;faction=阵营(ID);role=身份;match=局号><完整命令>`，大厅为 `[--:--]`。按 `teamColorId` 识别真实旁观阵营，避免误用 AI 也会设置的 `isTeamSpectator` 字段。
- 使用现有每帧任务队列观察首个有效对局帧，独立于命令到达，在统计开头记录本地日期/时间/时区和进入时游戏时间，区分连续对局及中途加入。
- `core/build.gradle.kts` 仅添加评估工具源目录；业务接线均在 `game/ai/custom/rwx_cli_tool`，未修改原版引擎和联机协议。
- 新增 6 项测试；说明在 `evaluate/utils/COMMAND_RECORDING.md`，启动文档已链接；自动导出文件单独忽略入库。

## 验证

- 完整 `:core:test`：24 套件、206 项、0 失败、0 错误；`:desktop:headlessArchive` 成功。
- 测试覆盖命令名合并/参数保留、真实进入时间首部、200 条并发写入、槽位与阵营区分、先记录再执行、真实 Socket 的 framed/base64/UTF-8/GBK/错误输入及最终导出。
- 真实三个独立 Windows JVM：Headless 托管房主 + 两个 CLI 游戏客户端，使用独立数据目录和端口文件，在 `Lake (2p)` 连续开局两次。
- 客户端 A/B 分别识别为阵营 `A(0)`/`B(1)`，房主为 `spectator(-3)`；第一局约 2 秒时的日志与 `rwx-game-state.gameTimeMillis` 对齐，第二局从零重新计时。
- 开局后只观察导出文件、不发送状态命令，也能获得三实例的真实进入时间；第二局追加独立时间记录。A 实例实际记录 `20:17:24.321` 和 `20:17:30.604 +08:00`。
- 房主 6 条/3 类，A 20 条/12 类，B 13 条/8 类；逐条命令数与各命令分类次数完全一致，包含未知命令和退出命令；`rwx-game-order-attack-mode` 的两组参数合并为 2 次。
- `git diff --check` 通过。验证进程均已退出，临时脚本、日志、隔离构建产物及本次生成的三份短回放清理完毕；其他任务的回放工具和输出保持原样。

## 复现构建

需要 Java 25。在仓库根目录创建临时隔离脚本后运行（输出、缓存和脚本均属于临时产物）：

```powershell
New-Item -ItemType Directory -Path agent-tmp -Force | Out-Null
'allprojects { layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("agent-tmp/priority-build/" + project.path.replace(":", "RWX_"))) }' | Set-Content -LiteralPath agent-tmp/priority-isolated.gradle -Encoding utf8
.\gradlew.bat :core:test :desktop:headlessArchive --init-script .\agent-tmp\priority-isolated.gradle --project-cache-dir .\agent-tmp\priority-gradle-cache --max-workers=1 --no-build-cache --no-configuration-cache '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

联机复现：启动三个实例，房主 `rwx-host --managed-room true --map "Lake (2p)"`，客户端分别以两个名字连接并设置 A/B 阵营；开局后先观察两个导出文件，再发送两种参数的 attack-mode 和 builder 的 push 命令，退出后核对日志条数及统计；双方断线返回大厅并重连后重复一局。

## 遗留

无。已运行的旧游戏进程需要重新编译并重启后才会启用新记录器；各游戏实例仅记录其自身 CLI 端口的请求。
