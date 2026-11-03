# 一次测完所有 CASE（被测 Agent 入口）

本文件给**被测 Agent**：一次评测要跑完全部四个微操 CASE 时，从这里开始。只做一个 CASE 时不需要本文，
直接看那个 CASE 目录下的 `START.md` 与命令手册即可。

> 你只会拿到本文件、各 CASE 的 `START.md` 与 `commands.md` / `command.md`。
> 场景数据、指标与评分口径属于评测方内部资料，不在你的可见范围内，也不需要它们才能打好。

---

## 1. 一次测试是什么

一次完整评测 = **四局互相独立的对局**，每局一个 CASE：

- 四局之间没有任何共享状态：单位、资金、地图、随机数都不继承。
- 每局由评测方用独立进程、独立数据目录、独立端口文件启动；局与局之间不要复用同一个 CLI 连接。
- 局内只能通过 §4 的方式观察和操作；不读源码、配置、评测目录或其它 run 的文件。
- 每局的 `task`、`seed`、赛道与预算由评测方指定；照用，不要自己改。

---

## 2. 四个 CASE 一栏表

| CASE | 一句话目标 | 赛道 | 开局 | 命令前缀 | 预算 |
|---|---|---|---|---|---|
| `CASE1-duel` 单挑 | 消灭全部敌方坦克，我方至少留一个单位 | realtime 或 pause | `rwx-micro-1-begin` | `rwx-micro-1-*` | 游戏 600 s / 墙钟 1200 s |
| `CASE2-LTE` 限时经济 | 把真实 `incomePerSecond` 做到本局目标值 | 仅 realtime | `rwx-micro-2-begin` | `rwx-micro-2-*` | 游戏 1200 s / 墙钟 1320 s |
| `CASE3-Transport` 军事运输 | 生产目标陆军并运到左上角目标岛 | 仅 realtime | **没有 begin**，进入即跑 | `rwx-micro-3-*` | 游戏 15–30 分钟，墙钟 = 游戏 + 120 s |
| `CASE4-Retreat` 战地撤退 | 摧毁左上角目标区域的全部敌人，善用右下角维修湾 | 仅 realtime | `rwx-micro-4-begin` | `rwx-micro-4-*` | 游戏 15–25 分钟，墙钟 = 游戏 + 120 s |

三件事在四个 CASE 里都一样，记一次就够：

1. **不允许暂停/变速/步进/重启/投降**（CASE1 的 pause 赛道是规则内的回合推进，不是暂停）；
2. **回执不等于执行**：`ok: submitted` 只表示请求受理，不代表单位已移动、已生产、已装载或已到达；
3. **时间在跑**：realtime 下查询、思考和传输期间游戏继续推进。

---

## 3. 一次测完的启动顺序

以下命令都在**仓库根目录**执行，使用 Windows PowerShell。把尖括号里的值换成评测方给的实际值。
**每局换一个新的数据目录与端口文件**；端口文件里的端口由游戏写入，不要自己指定、也不要硬编码。

```powershell
# ① CASE1-duel（示例 task=1_2_2, seed=1, track=pause；realtime 把 track 换掉）
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE1-duel --case-args task=<1_x_y>,seed=<n>,track=<realtime|pause> --data-dir=agent-operation/tmp/eval-c1 --cli-port-file=agent-operation/tmp/eval-c1/port'

# ② CASE2-LTE（示例 task=2_5_3, seed=1）
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE2-LTE --case-args task=<2_Y_T>,seed=<n>,track=realtime --data-dir=agent-operation/tmp/eval-c2 --cli-port-file=agent-operation/tmp/eval-c2/port'

# ③ CASE3-Transport（示例 task=3_1_1, seed=1）
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE3-Transport --case-args task=<3_type_level>,seed=<n>,track=realtime --data-dir=agent-operation/tmp/eval-c3 --cli-port-file=agent-operation/tmp/eval-c3/port'

# ④ CASE4-Retreat（示例 task=4_1_1, seed=1）
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE4-Retreat --case-args task=<4_type_level>,seed=<n>,track=realtime --data-dir=agent-operation/tmp/eval-c4 --cli-port-file=agent-operation/tmp/eval-c4/port'
```

- 启动命令会持续占用该终端；**必须另开终端**运行 CLI 客户端。
- 想旁观就把 `--headless` 换成 `--window`（两者互斥，其余参数不变）。
- 首次启动可能要构建几分钟。看到 `RWX_MICRO_READY` 之后再连。

建议顺序就是 ①→②→③→④：CASE1 有 pause 赛道，适合先确认连接与命令格式；CASE3/CASE4 时间最长，放后面不容易被前面积压的会话拖住。

**如果评测方已经启动了实例**，不要重复启动，直接用对方给的端口文件。

---

## 4. 通用连接方式（四个 CASE 完全一样）

```powershell
python agent-operation/tools/rwx-cli.py --port-file <端口文件> --stdio
```

1. 启动客户端，等 stderr 出现 `ready`。
2. 向 stdin 写**一行**命令并 flush；从 stdout 读**一行** JSON。
3. 外层是 `{"error":false,"reply":"…"}`；命令带 `--json` 时，`reply` 里还有一层 JSON，要再解析一次。
4. 读到回复再发下一条。**整局复用同一个客户端与同一条标准流**，不要每条命令都重开进程。

人工手动试跑时可以改用 `--interactive`（逐行输入，直接看文本回复）：

```powershell
python agent-operation/tools/rwx-cli.py --port-file <端口文件> --interactive
```

已经确定的连续几条命令可以用 `--batch-file`（UTF-8，每行一条，空行与 `#` 注释忽略）；它不会根据回复调整后续命令，
也不是原子事务。

断线或超时后：**先重连并查询状态**，再决定是否重发，避免重复提交已经执行过的操作。

---

## 5. 每个 CASE 的第一步

| CASE | 第一步 | 说明 |
|---|---|---|
| CASE1 | `rwx-micro-1-state` → `rwx-micro-1-units` → `rwx-micro-1-begin` | briefing 期可以观察，默认也可以下令；`begin` 后 pause 每回合用 `next` 推进 |
| CASE2 | `rwx-micro-2-state --json` → `-builder --json` → `-resources --json` → `rwx-micro-2-begin` | 目标收入只在开局后由 `state` 公布，启动前不会告诉你 |
| CASE3 | `rwx-micro-3-state --json` → `-goal --json` → `-production/-fleet --json` | 进入即在跑；目标组成只在开局后由 `goal` 公布 |
| CASE4 | `rwx-micro-4-state --json` → `rwx-game-map-show` → `rwx-game-unit-list --owner self --full --json` → `rwx-micro-4-begin` | 敌军配置、数量、坐标不会公开；迷雾下的敌人只能依赖实际可见信息 |

每个 CASE 的完整命令表、参数、错误语义与字段说明，都在该 CASE 的 `commands.md` / `command.md` 里；
`rwx-micro-<n>-help --list` 可以列出章节，`rwx-micro-<n>-help 3` 只返回第 3 节。

---

## 6. 结束与判定

| outcome | 含义 |
|---|---|
| `success` / `win` | 达成该 CASE 的目标 |
| `defeat` / `loss` | 我方关键单位全灭 |
| `draw` | 同一 tick 内双方同归于尽（CASE1） |
| `timeout` | 预算耗尽仍未分出结果 |
| `error` | 对局异常，例如一直没接入命令（`no-agent`） |

- 结束后进程随即退出：headless 打印 `RWX_MICRO_RESULT ...`；窗口/人类模式播完结算画面后按倒计时退出。
- **不要依赖结束后的继续查询**，`phase=ended` 之后不再接受操作。
- 结局由引擎判定。评测方在局后另有离线分析（材料与口径属于评测方内部资料），它不改变胜负，
  也不改变你局内看到的任何状态。

---

## 7. 参与边界

- 局内只通过本文与各 CASE 手册列出的接口观察、操作游戏。
- 不得读取源码、配置、引擎证据、`RWX_MICRO_RUN` 指向的目录、其它 run 或额外资料；输出目录即使在文件系统中可见，
  也不属于你的观察接口。
- 不得修改单位、地图、敌方行为、时限或赛道；不得伪造或修改结果。
- 一次正式评测里，每个 CASE 只有一局；不得重开、重启、投降或用白名单外命令绕过限制。

越权或白名单外的请求会在执行前被拒绝，单独记录，不进录像、不改游戏状态。

---

## 8. 常见错误

| 现象 | 原因 |
|---|---|
| 发了 `begin` 却报 unknown command | CASE3 没有 `begin`，进入即跑 |
| 一直停在 briefing | 忘了 `begin`。CASE1/2/4 不 `begin` 游戏时间不推进，只有墙钟预算在烧 |
| 以为 `next` 能加速 | 只有 CASE1 的 pause 赛道有 `next`；其余赛道发了就是 `rejected` |
| 算了半天回合数，游戏时间却对不上 | pause 一回合 60 tick，引擎时钟只有 **0.960 s**，不是 1 秒；`turnSeconds` 是名义秒 |
| 命令返回 `ok: submitted` 但单位没动 | 受理 ≠ 生效；查 `units` / `state` 的实际状态与 `pending` |
| 一条命令改了半天状态没变化 | 可能引用了已死单位或被后续命令覆盖；用 `state` 重新确认对象 |
| 以为连接断了就该重开一局 | 先重连查询；一局只有一次机会，重开会作废本次结果 |
