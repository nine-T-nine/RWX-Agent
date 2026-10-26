# RWX CLI 启动入口

所有命令从仓库根目录执行，需要 Java 25。GUI 与 Headless 使用同一套游戏协议和 CLI；完整命令见 [agent-operation/help.md](agent-operation/help.md)。

Windows PowerShell：

```powershell
.\gradlew.bat :desktop:rwxCliRun
# 无窗口运行，另一个终端或 Agent 继续发送命令：
.\gradlew.bat :desktop:run '--args=--headless --no-steam'
```

Linux / WSL：

```bash
bash ./gradlew :desktop:run --args='--headless --no-steam'
```

看到 `RWX_CLI_TOOL_READY` 后，使用自动发现的本地 CLI 端口：

```bash
python3 agent-operation/tools/rwx-cli.py rwx-connection-status
python3 agent-operation/tools/rwx-cli.py rwx-connect <server-address>:5123 --name AgentA
python3 agent-operation/tools/rwx-cli.py rwx-connection-status
```

Windows 把 `python3` 换成 `python`，或使用：

```powershell
.\agent-operation\tools\rwx-cli.ps1 rwx-connect <server-address>:5123 --name AgentA
```

`5123` 是多人游戏端口；随机 CLI 端口写入 `agent-operation/tmp/port`，只绑定本机回环地址。连接回执仅表示已请求连接；以 `rwx-connection-status` 的 `joined` / `in-game` 确认注册成功。

单机玩法、多个实例、连接与退出流程见 [Agent 操作指南](agent-operation/START.md)。Ubuntu 服务器部署、systemd 和远程 Agent 示例见 [服务器指南](agent-operation/SERVER.md)。
