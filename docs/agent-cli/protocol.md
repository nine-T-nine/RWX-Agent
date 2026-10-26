# RWX 本机 agent CLI 协议（阶段一）

## 传输

TCP，地址 `127.0.0.1:<port>`。端口每次启动随机，监听成功后原子写入 `agent-operation/tmp/port`，文件只含十进制数字。可用 `--cli-port-file=<路径>`、`-Drwx.cli.portFile=<路径>` 或 `RWX_CLI_PORT_FILE` 覆盖；启动参数优先。启动日志保留 `RWX_CLI_TOOL_READY port=<n> host=127.0.0.1`，并报告主文件及实例文件路径。主文件由进程锁协调，多实例不互相覆盖；读取后必须 `rwx-ping` 验证以排除异常退出留下的失效记录。此端口仅用于本地 CLI，与多人游戏端口无关。

所有收发文本固定为 **UTF-8**：服务端回复永远是 UTF-8；客户端把命令行按 UTF-8 发送即可。旧客户端若按 GBK 发送，服务端会退一步用 GBK 解码该行，但回复仍按 UTF-8。

两种请求形式：

- **一次性**：发一行请求，半关闭写端，读到 EOF。适合脚本单条调用。
- **常驻（framed）**：先发一行 `!!framed`，服务端回 `\x1e ready \x1e`；之后每条回复是 `\x1e <文本> \x1e`（ASCII RS 分隔），多行输出不需要额外结束标记。适合每轮几十条命令的控制循环。

请求行有两种写法：

- 规范形式：`<命令><TAB><base64(argv1)>,<base64(argv2)>,...`，参数可以含空格和任意 Unicode。
- 纯文本形式：空白分隔；值里有空格时用双引号或单引号包住（`--name "Lake (2p)"`），反斜杠可转义下一个字符。

## 查询命令

| 命令 | 返回 | 说明 |
| --- | --- | --- |
| `rwx-ping` | `pong` | 连通性检查 |
| `rwx-game-state --json` | JSON | 一次拿到资金/收入、每个单位的命令与队列、每个工厂的升级进度和可用动作 |
| `rwx-game-state --summary --json` | JSON | 宏观摘要：tick/时间/暂停/速度/地图/资金/收入/单位数 |
| `rwx-game-brief --json` | JSON | 全局摘要：经济、兵力、生产、受损、敌情、战果 |
| `rwx-game-unit-list --json` | JSON | 单位列表与按类型汇总 |
| `rwx-game-threat-list --json [--cluster]` | JSON | 威胁明细；`--cluster` 时含集群中心/血量/兵种构成 |
| `rwx-game-alerts --json` | JSON | 去重后的攻击/损失/正在被攻击单位 |
| `rwx-game-map-show --terrain [--ascii]` | 文本 | 地形窗口；默认分类数字，`--ascii` 时字符图 |

`rwx-help` 返回内置的完整命令手册。

## 命令

| 命令 | 示例 | 效果 |
| --- | --- | --- |
| `rwx-game-order-move` | `--units 42,43 --x 900 --y 650` | 普通移动 |
| `rwx-game-order-attack-move` | `--units 42,43 --x 900 --y 650` | 攻击移动 |
| `rwx-game-order-attack` | `--units 42,43 --target 91` | 攻击可见目标（只接受目标 id） |
| `rwx-game-order-stop` | `--units 42,43` | 清除当前命令 |
| `rwx-game-restart` | | 同一进程内重载当前地图（保留房间设置/队伍/资金），用于重开 |
| `rwx-game-surrender` | | 发送引擎的 `-surrender` |

`--units` 是逗号分隔的 object id，也可用 `all-army`、`all-units`、`group:<name>` 或区域选择器（`area:` / `rect:`）；类型筛选用 `--type`，旧的 `--units type:...` 写法已删除。命令只能操作本地玩家队伍的可控制活单位。成功回执以 `Command accepted` 开头，失败时以 `error:` 开头且不提交命令。

## 边界

端点仅在活跃单机战斗中接受语义命令。多人游戏、回放、非活动战局、其他队伍单位与失去视野的攻击目标都会被拒绝。端口只绑定回环地址，不能远程访问。
