# 云端部署记录（Ubuntu 24.04 / 地址已脱敏）

本文记录 2026-10-25 的实际部署与验证结果。SSH 密码只通过环境变量传入一次性脚本，未写入任何文件。

## 目标机

| 项 | 值 |
|---|---|
| 系统 | Ubuntu 24.04 LTS，内核 6.8，x86_64 |
| CPU / 内存 | 2 vCPU / 1967 MiB（部署时可用约 1.2 GiB），2 GiB swap |
| 磁盘 | 50 GB，根分区剩余 38 GB |
| 已有服务 | 80/443/28785 已占用；5123 空闲 |
| 防火墙 | 云安全组已放行 5123；`ufw` 处于 inactive |
| Java | 部署前未安装；使用随包上传的 Temurin 25 JRE |

## 安装内容

| 路径 | 说明 |
|---|---|
| `/opt/rwx/java25` | 指向 `/opt/rwx/java25-<sha12>` 的符号链接 |
| `/opt/rwx/releases/<日期>-<sha12>` | 解压的 Headless 发行包，`/opt/rwx/current` 指向它 |
| `/etc/systemd/system/rwx.service` | 常驻服务，`User=rwx`，`/var/lib/rwx` 为数据目录 |
| `/etc/rwx/rwx.env` | `RWX_JAVA_OPTS="-Xms64m -Xmx512m -XX:ActiveProcessorCount=2"` |
| `/var/lib/rwx` | 数据目录；CLI 端口文件在 `agent-operation/tmp/port` |

`ExecStart` 跑 `bin/rwx-headless`；`ExecStartPost` 等待本地 CLI 就绪后执行
`rwx-host --port 5123 --managed-room true`，服务器自己占旁观席，由首位客户端担任大厅管理员。

## 部署与升级步骤

`sftp-put.py` 与 `ssh-run.py` 从环境变量 `RWX_SSH_PASSWORD` 读取密码，不写文件、不进命令行：

```powershell
$env:RWX_SSH_PASSWORD = '<临时密码>'   # 只存在于当前 PowerShell 会话

# 本机：打包并上传（Java 运行时只需首次上传）
wsl -d Ubuntu-22.04 -- sh -c "cd /mnt/x/game/RWX/agent-tmp/java25-linux && tar -czf ../java25-linux-runtime.tar.gz jdk-25.0.5+8-jre"
python agent-operation/server/sftp-put.py agent-tmp/priority-build/RWX_desktop/distributions/rwx-headless.tar.gz /tmp/rwx-headless.tar.gz
python agent-operation/server/sftp-put.py agent-tmp/java25-linux-runtime.tar.gz /tmp/rwx-java25.tar.gz
python agent-operation/server/sftp-put.py agent-operation/server/install-cloud.sh /tmp/rwx-install.sh
python agent-operation/server/ssh-run.py 'sha256sum /tmp/rwx-headless.tar.gz /tmp/rwx-java25.tar.gz'

# 服务器：校验并安装/升级（脚本会先停旧服务、装新 release、切换 symlink、重启）
python agent-operation/server/ssh-run.py 'sudo bash /tmp/rwx-install.sh /tmp/rwx-headless.tar.gz /tmp/rwx-java25.tar.gz <package_sha> <runtime_sha>'
```

`install-cloud.sh` 是可重复执行的升级入口：校验两个 tar 的 sha256、拒绝覆盖非符号链接的
`/opt/rwx/current`、检查 5123 是否被占用、保留上一份 `rwx.service` 到 release 目录、按需写
`/etc/rwx/rwx.env`（已存在则不覆盖）、`systemd-analyze verify` 通过后才 `enable --now`。

## 验证结果（2026-10-25 18:0x）

- 服务：`systemctl status rwx` = active；`ss -lnptu` 显示 TCP/UDP `*:5123` 由 java 监听；
  `rwx-connection-status` = `hosting`，`gamePort: 5123`，`spectator: true`。
- 公网联机（Windows 客户端 → `<server-address>:5123`）：
  - 首个客户端加入即成为大厅管理员，可改地图/收入/AI 并开局；
  - 第一局 tick 正常推进（实测 110 tick 后保持运行 8 秒不回大厅）；
  - 对局进行中第二个客户端加入 → 自动观战（`spectator: true`）；
  - 管理员退出 → 对局结束回大厅，由仍在线的客户端接任管理员；
  - 第二局由新管理员开局，tick 正常推进（78 tick + 8 秒保持）。
- 服务端日志可用 `sudo journalctl -u rwx -f` 查看；客户端状态包、托管房间事件会打印
  `RWX_CLIENT_STATUS` / `RWX_SERVER_STATUS` / `RWX_ROOM_*` 行。

本机可重复的回归（Headless 房主 + 三个客户端，覆盖选举/移交/中途加入/连续两局）：

```powershell
python agent-operation/tools/verify-managed-room.py --host-wsl Ubuntu-22.04 --rounds 2
```

## 自定义地图

服务器数据目录下的 `mods/maps` 就是可选地图来源（虚拟路径 `/SD/rustedWarfare/maps`）。同步一次即可长期使用：

```bash
# 本机打包（WSL tar 处理中文文件名）
wsl -d Ubuntu-22.04 -- sh -c "cd /mnt/x/game/RWX/mods && tar -czf /mnt/x/game/RWX/agent-tmp/mods-maps.tar.gz maps"
python agent-operation/server/sftp-put.py agent-tmp/mods-maps.tar.gz /tmp/rwx-mods-maps.tar.gz

# 服务器解包
sudo tar -xzf /tmp/rwx-mods-maps.tar.gz -C /var/lib/rwx/mods
sudo chown -R rwx:rwx /var/lib/rwx/mods
rm /tmp/rwx-mods-maps.tar.gz
```

2026-10-25 实际同步了 326 张图（652 个 tmx/png 文件，818 MB），磁盘 50 GB 中已用 12 GB、剩 36 GB。
服务器只在被请求时扫描清单（每张图只读 64 KB 文件头做特性探测），不会把地图预读进内存；
唯一的内存大头是当前对局加载的那一张图。

客户端也需要本地存在同名地图文件（原版联机按路径加载，不传地图），否则选到该图后加载会失败。
服务器上找不到玩家选的地图时，`-map` 请求会被拒绝并提示 `Map is not enabled`。

## 运行与维护

```bash
sudo systemctl status rwx --no-pager     # 状态
sudo journalctl -u rwx -f                # 日志
sudo systemctl restart rwx               # 重启（房间会重建）
sudo systemctl disable --now rwx         # 停用
```

房间端口固定 5123。CLI 只监听本机回环，远程管理请走 SSH 登录后执行
`runuser -u rwx -- python3 /opt/rwx/current/agent-operation/tools/rwx-cli.py --port-file /var/lib/rwx/agent-operation/tmp/port ...`。

## 已知边界

- 服务器只做“常驻房主 + 完整模拟”，同步仍是原版确定性锁步；不是“服务器权威、客户端纯显示”。
- `-Xmx512m` 对 2 vCPU/2 GiB 的机器够用；玩家或 AI 很多时需要调 `/etc/rwx/rwx.env` 并观察内存。
- 云安全组已放行 5123；`ufw` 未启用，如以后开启需补 `ufw allow 5123/tcp` 与 `5123/udp`。
- 未配置 TLS、认证或房间口令；需要时用 `rwx-host --password-env RWX_ROOM_PASSWORD`（`RWX_ROOM_PASSWORD`
  从 `/etc/rwx/rwx.env` 注入，不要写进命令行或仓库）。
- RELAY-CN 公共中继仍因版本原因拒绝加入（`Your client is newer then the server`），与本部署无关。
