# 记录 CLI 命令与使用次数

[`CommandRecorder.java`](CommandRecorder.java) 从游戏的本机 CLI Socket 收包入口记录完整命令，在 `evaluate/output/` 自动生成 UTF-8 文本日志和统计文件。重新编译并启动游戏即可使用，无需替换发送命令的客户端。

## 获取输出

```powershell
.\gradlew.bat :desktop:rwxCliRun
# 或无窗口运行：
.\gradlew.bat :desktop:run '--args=--headless --no-steam'
```

启动日志的 `RWX_CLI_COMMAND_RECORDING` 行给出两个文件的绝对路径。默认在仓库的 `evaluate/output/` 中，每个监听实例独立生成：

- `commands-地图名-日期--时分.txt`（如 `commands-4P绿洲湖畔byConfetti-20261027--23-03.txt`）：每条接收到的完整命令，实时追加并刷新。启动时还没有地图，先命名为 `commands-lobby-日期--时分.txt`；第一局开始后改名，大厅期间的命令仍保留在同一文件。时分是记录器启动时的本地时间（24 小时制 `HH-mm`），改名后保持不变。地图名中的空格和 Windows 非法字符替换为单个 `-`；同一进程内的后续对局继续写这个文件，每局地图记在统计首部。同一分钟内启动两次时后者自动加 `-2`、`-3` 后缀（同一分钟、同一地图的改名同理），不会覆盖已有文件。
- 同名 `-stats.txt`：实际进入对局时间、命令总数、种类数、每种命令次数及执行者分项统计，每秒刷新，正常退出时再次导出。

改名后会再打印一行相同格式的 `RWX_CLI_COMMAND_RECORDING`，其中的路径为最终文件名。

Java 调用方可以通过 `export()` 立即导出统计，`close()` 完成最终导出。需要自定义目录时，在启动 JVM 时设置 `-Drwx.cli.recordingDir=<目录>`。目录不可写时游戏日志会说明记录失败，命令本身继续按原流程执行。

## 读取命令与时间

```text
[01:05]<name=AgentA;player=0;faction=A(0);role=player;match=1><rwx-game-order-attack-mode --apply-to-new --mode off>
[01:06]<name=AgentA;player=0;faction=A(0);role=player;match=1><rwx-game-order-attack-mode --units 42 --mode holdFire>
[01:08]<name=AgentA;player=0;faction=A(0);role=player;match=1><rwx-game-order-push --units 42 --x 300 --y 400>
```

`[分:秒]` 是收到命令时的当前对局时间，直接读取 `GameEngine.gameTimeMillis`，在命令执行前冻结；暂停时不前进，加速时跟随游戏时间。超过一小时显示 `[60:00]`，分钟不归零。大厅或尚未进入对局的命令显示 `[--:--]`，`match=none`。

执行者取该端口所属游戏实例的当前控制玩家：对局中取 `engine.playerTeam`，大厅中取网络本地玩家。`player` 是引擎的玩家槽 ID，`faction` 是联盟阵营及其原始 ID，两者可以不同。旁观者标为 `role=spectator`，尚未分配玩家时标为 `unassigned`，不根据命令的 `--units` 或目标参数猜测执行者。

`match` 区分同一进程内的连续对局。统计文件**开头**记录每局实际进入时的本地日期、时间和时区，同时保留进入时的游戏时间；中途加入时游戏时间可以大于零。进入事件通过引擎现有的每帧任务队列观察，在首个有效对局帧记录，独立于命令到达时间。

纯文本命令保留收到的内容；`tab + base64 argv` 协议解码为可读的完整命令，有空格或空值的参数加引号。日志中的反斜杠、换行、制表符及尖括号使用 `\\`、`\n`、`\t`、`\u003c`、`\u003e` 等转义，确保一条完整命令只占一行。

## 统计口径

上例按命令名汇总为：

```text
rwx-game-order-attack-mode	2
rwx-game-order-push	1
```

参数不参与分组，命令名忽略大小写。包含查询、`rwx-ping`、`rwx-help`、未知命令及参数验证失败的命令；统计的是接收次数。`!!framed` 属于传输握手，不计为命令。

每个实例只记录发到自身 CLI 端口的命令。要覆盖联机双方，分别收集双方实例的输出；同一端口上的多个外部程序共享游戏内玩家身份，协议本身没有外部操作者 ID。鼠标操作、游戏网络包和自动微操内部派生的动作不作为额外 CLI 命令重复统计。

## 集成位置

- 收包入口：[`RwxCliTool.serveClient`](../../core/src/main/java/com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/RwxCliTool.java)，握手处理后、`handle()` 前记录。
- 游戏身份与时间桥接：[`RwxCliCommandCapture`](../../core/src/main/java/com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/RwxCliCommandCapture.java)。通过现有帧任务队列观察对局进入，无需修改原版引擎生命周期。
- `core/build.gradle.kts` 将 `evaluate/utils` 加入 Java 源目录，工具随 core 一起打包，GUI 和 Headless 共用。
