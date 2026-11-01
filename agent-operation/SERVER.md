# RWX Headless 多人服务器与 Agent 联机

## 源码结论与方案选择

| 方案 | 模拟位置与依赖 | 适用性 |
|---|---|---|
| GUI RWX 托管 | 房主及各客户端运行完整引擎；桌面窗口、渲染和音频 | 可用，但云端额外占资源 |
| Headless RWX 托管 | 同一个完整引擎和原版网络；Java 25，无窗口、GPU、音频设备或 Xvfb | 本项目推荐方案；旁观房主加多个独立 Agent 客户端 |
| 独立 Dedicated Server | 源码有 dedicated 分支及转发协议，但没有独立的 dedicated 可执行模块/启动器 | 本次不另造一套服务器协议 |
| 公共 RELAY-CN | 由主服务器列表发现入口，用原版查询串/转发协议加入；可由客户端成为房主 | 依赖第三方入口及其房间命令，不代替各客户端的完整模拟 |

核心源码：

- [NetworkEngine.java](../core/src/main/java/com/corrodinggames/rts/gameFramework/network/NetworkEngine.java)：`b(String, boolean)` 解析直连、短中继码、`get|...`；默认游戏端口 **5123**，TCP 建连超时 7 秒；`startServerHosting` 同时开放 TCP 和可靠 UDP。
- [ConnectionAcceptor.java](../core/src/main/java/com/corrodinggames/rts/gameFramework/network/ConnectionAcceptor.java)：原版游戏监听及连接接收，默认绑定所有接口；公网可达性取决于安全组、防火墙和 NAT。
- [MasterServerClient.java](../core/src/main/java/com/corrodinggames/rts/gameFramework/network/MasterServerClient.java)、[ServerInfo.java](../core/src/main/java/com/corrodinggames/rts/gameFramework/network/ServerInfo.java)：公共列表、连接描述符和动态解析；`RELAY-CN` 是列表标签，不是固定 DNS 名称。
- [GameLogic.java](../core/src/main/java/com/corrodinggames/rts/game/GameLogic.java)、[CommandController.java](../core/src/main/java/com/corrodinggames/rts/gameFramework/CommandController.java)：所有参与者运行模拟；房主调度命令和阻塞帧，客户端通过原版命令队列提交操作。

原版不是服务器持续下发所有单位坐标的模式，也不是 CLI 直接操控服务器内存。握手包含预注册 `160/161`、玩家注册 `110`、房间配置/队伍 `106/115`，开局 `120`；客户端命令 `20`、房主命令批次 `10`，校验 `30/31` 和重同步 `35`。中继版本/成为房主/转发使用 `163/170/172..176`。房主为普通玩家的命令补上真实 `sourceTeam`，并校验权限；Agent 没有特殊授权。

Headless 使用现有 Session、房间控制器、游戏循环、寻路和网络；仅绘制为空操作，保留图像尺寸和必要的 CPU 像素读取。Java AWT 只用于无窗口事件调度与 ImageIO，设置 `java.awt.headless=true`，不创建任何窗口或 OpenGL 上下文。依赖使用图形窗口/GPU 的第三方 JVM Mod 不属于此最小运行方案；联机应使用一致的游戏版本、单位配置和模组。

## 三种生命周期

```text
RWX 进程（GUI 或 Headless）
  ├─ CLI Server：随机本地回环端口 → Agent 读取 agent-operation/tmp/port
  │    └─ Python / PowerShell 控制连接，可随时关闭，不影响游戏连接
  └─ Game 网络连接：rwx-connect → 房间注册 → 开局 → 锁步模拟
       └─ rwx-disconnect 只断游戏；rwx-exit 才退出进程与 CLI
```

每个 Agent 都运行自己的 RWX。服务器房主可以是旁观者，仍运行模拟并负责调度。公网只需游戏端口；本地 CLI 也可通过用户主动建立的 SSH 隧道管理，但默认不对外监听。

## 1. 只读环境检查

从本机登录，使用已配置的 SSH 密钥或交互密码；不要把密码写进脚本、环境文件或 Git：

```bash
ssh <username>@<server>
```

登录后执行：

```bash
id
cat /etc/os-release
uname -m
free -h
df -h / /tmp
java -version
python3 --version
ss -lntup
sudo ufw status verbose
```

项目编译目标为 Java 25，Java 11/17/21 无法运行生成的 class。运行发行包只需要 Java 25 Runtime；在服务器构建才需要 JDK 25。下面下载命令按 `x86_64` / `aarch64` 自动选择；其他架构先停止并核对支持情况。

## 2. 本机构建与上传（确认后上传）

仓库根目录，Windows：

```powershell
.\gradlew.bat :core:test :desktop:headlessArchive --max-workers=1
scp desktop/build/distributions/rwx-headless.tar.gz <username>@<server-address>:~/rwx-headless.tar.gz
```

Linux / WSL：

```bash
bash ./gradlew :core:test :desktop:headlessArchive --max-workers=1
scp desktop/build/distributions/rwx-headless.tar.gz <username>@<server-address>:~/rwx-headless.tar.gz
```

包内有 `lib/*.jar`、`bin/rwx-headless`、Python CLI、systemd 模板和启动后托管脚本。没有 SSH 密码。Headless 的图像和模拟适配不加载 GUI 本机库；普通 GUI 包仍需针对目标平台构建。

## 3. 服务器安装（已按 DEPLOYMENT.md 执行）

以下安装到 `/opt/rwx/`，游戏可写数据在 `/var/lib/rwx/`，新增无登录用户 `rwx`。不修改系统默认 Java。

```bash
sudo apt-get update
sudo apt-get install --no-install-recommends ca-certificates curl python3 tar
getent group rwx || sudo groupadd --system rwx
id rwx || sudo useradd --system --gid rwx --home-dir /var/lib/rwx --shell /usr/sbin/nologin rwx
sudo install -d -m 0755 /opt/rwx/releases /opt/rwx/java25 /etc/rwx
sudo install -d -o rwx -g rwx -m 0750 /var/lib/rwx
```

下载官方 Temurin Java 25 JRE，并核对 API 返回的 SHA-256。URL 和校验和来自同一份版本元数据，不拼接猜测文件名：

```bash
case "$(uname -m)" in
  x86_64) rwx_arch=x64 ;;
  aarch64) rwx_arch=aarch64 ;;
  *) echo 'Unsupported architecture'; exit 1 ;;
esac
curl -fLS --max-time 60 -A 'RWX/1.1.3' \
  "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$rwx_arch&image_type=jre&os=linux&vendor=eclipse" \
  -o /tmp/rwx-java25.json
mapfile -t rwx_java_meta < <(python3 -c 'import json; p=json.load(open("/tmp/rwx-java25.json"))[0]["binary"]["package"]; print(p["link"]); print(p["checksum"])')
curl -fL --max-time 300 "${rwx_java_meta[0]}" -o /tmp/rwx-java25.tar.gz
printf '%s  %s\n' "${rwx_java_meta[1]}" /tmp/rwx-java25.tar.gz | sha256sum -c -
sudo tar -xzf /tmp/rwx-java25.tar.gz --strip-components=1 -C /opt/rwx/java25
/opt/rwx/java25/bin/java -version
```

安装新发行版本并保留旧目录以便回滚：

```bash
rwx_release="$(date -u +%Y%m%d-%H%M%S)"
sudo install -d "/opt/rwx/releases/$rwx_release"
sudo tar -xzf "$HOME/rwx-headless.tar.gz" -C "/opt/rwx/releases/$rwx_release"
sudo ln -sfn "/opt/rwx/releases/$rwx_release" /opt/rwx/current
sudo install -m 0644 /opt/rwx/current/agent-operation/server/rwx.service /etc/systemd/system/rwx.service
```

模板设置：768 MiB Java 堆上限，服务器旁观，`Lake (2p)`，TCP/UDP `5123`。首位客户端获得大厅管理权，由管理员配置和手动开局。实际常驻内存、寻路与玩家数有关，安装前按步骤 1 的空闲内存调整 `RWX_JAVA_OPTS`，不要把堆上限当作进程实际内存。

可选 `/etc/rwx/rwx.env` 只放游戏设置，例如 `RWX_JAVA_OPTS`、`RWX_ROOM_PASSWORD`。如果设置房间密码，将文件权限设为 `0600` 并由 root 持有；**不要放 SSH 密码**。`host-service.py` 通过 `--password-env RWX_ROOM_PASSWORD` 引用，不把游戏密码打印到命令或日志。

## 4. 游戏端口与网络

先检查 `5123` 未被其他程序占用：

```bash
sudo ss -lntp 'sport = :5123'
sudo ss -lnup 'sport = :5123'
```

如果 Ubuntu 已启用 UFW，添加游戏端口；不要为了本部署覆盖已有 SSH 规则或直接重置防火墙：

```bash
sudo ufw allow 5123/tcp comment 'RWX game TCP'
sudo ufw allow 5123/udp comment 'RWX reliable UDP'
sudo ufw status verbose
```

在云控制台的实例安全组放行 TCP/UDP `5123`，优先限制为 Agent 所在网络，保留现有 SSH 入站规则。若服务器经过 NAT，还需要相应端口转发。默认 CLI 只绑定 `127.0.0.1`，不要给随机 CLI 端口添加公网规则。

## 5. systemd 启动、验证与维护

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now rwx.service
sudo systemctl status rwx.service --no-pager
sudo journalctl -u rwx.service -n 100 --no-pager
sudo ss -lntp 'sport = :5123'
sudo ss -lnup 'sport = :5123'
sudo -u rwx python3 /opt/rwx/current/agent-operation/tools/rwx-cli.py \
  --port-file /var/lib/rwx/agent-operation/tmp/port rwx-connection-status
```

启动后 `ExecStartPost` 等待本地 CLI 就绪，再提交 `rwx-host --managed-room true`。状态应为 `hosting`；首位客户端注册后取得大厅管理权，由其在 GUI 或 CLI 选择地图、配置并开局。需要更多对局玩家时换用足够出生点的地图。

```bash
sudo journalctl -u rwx.service -f
sudo systemctl stop rwx.service
sudo systemctl restart rwx.service
sudo systemctl disable --now rwx.service
```

更新：先停止服务，按步骤 2 上传新包，解包到一个新的 releases 目录，切换 `current` 后启动。保留 `/var/lib/rwx` 维护服务器设置；所有 Agent 同步更新游戏/单位定义。回滚时停止服务，再将 `current` 指向保留的旧版本。

托管模式中，所有在线非旁观玩家都退出或战败后，服务器发送原版返回大厅事件；连接保留，AI 单独运行不会阻止返回。管理员只在大厅选举和移交；对局中即使原管理员离开也不移交。新身份在局中加入时自动观战，原玩家断线重连保留原席位。回到大厅后，观战者可选择出生点参加下一局，无需重启服务。

## 6. 多 Agent 加入

Windows Agent A（从自己的仓库根目录）：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --data-dir=agent-operation/tmp/a --cli-port-file=agent-operation/tmp/a/port'
# 另一个终端，或由 Agent 在后台启动上面的进程后执行：
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connect <server-address>:5123 --name AgentA
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/a/port rwx-connection-status
```

Linux / WSL Agent B：

```bash
bash ./gradlew :desktop:run --args='--headless --data-dir=agent-operation/tmp/b --cli-port-file=agent-operation/tmp/b/port'
# 另一个终端：
python3 agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/b/port rwx-connect <server-address>:5123 --name AgentB
python3 agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/b/port rwx-connection-status
```

若从发行包启动，使用 `bash bin/rwx-headless --data-dir=... --cli-port-file=...`。这个启动器固定为 Headless。GUI 客户端从源码使用 `:desktop:rwxCliRun` 或 `:desktop:run --args='--rwx-cli'`，或使用目标平台的 GUI 安装包；连接步骤相同。客户端名、数据目录、端口文件各不相同；服务器按持久客户端身份识别重连，不能靠改名冒充其他玩家。

确认 `in-game` 后：

```text
rwx-game-state --summary
rwx-game-unit-list --owner self --full --compact
rwx-game-order-move --units <己方实际ID> --x <地图内X> --y <地图内Y>
rwx-disconnect
rwx-connect --reconnect true
```

这里的 `<...>` 来自查询结果，不是可直接照抄的单位 ID。普通玩家不能替其他 Agent 下令，也不能调用房主的地图、AI、房间设置或开局权限。重连仍受原版房主政策与原队伍是否保留限制。

