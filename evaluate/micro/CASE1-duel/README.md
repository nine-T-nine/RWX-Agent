# CASE1-duel（单挑）

小规模 1vN 遭遇战：用给定的机枪机甲消灭对方全部猛犸坦克。考察的是**交火后的反应速度与火力利用**，
不是运营。一局 10 分钟游戏时间以内，可暂停思考，也可实时连打。

本文件只讲**怎么跑、场景长什么样、材料在哪**。每个指标的口径、阈值和已知局限见 [METRICS.md](METRICS.md)。

---

## 1. 场景布置

### 1.1 地图

`task` 第二位选地图类型。

**类型 1 — 程序生成草地**（长宽在 80~120 格之间由 seed 决定）

- 随机扰动分三步：
  1. 定数量：悬崖 5~10 个、水域 5~10 个，合计 N=10~20；
  2. 用 **Best-candidate 采样**（泊松圆盘的实用替代）一次撒 N 个中心：**第一个中心固定为"我方出生点朝敌方走 1/3 或 1/4 处"且必定是水域**
     （保证 `A–水域–B` 的开局结构），其余中心挑"到已选中心最近距离"最大的候选点，分数带 ±15% 抖动，
     目标间距 `0.65×√(WH/N)`，中心离出生点至少 8 格；
  3. 每个中心用**随机边界生长**长出 10~20 格：每次从当前边缘集合里随机挑一格并入区域，再把它的空地邻居加进边缘集合
     （不是 FIFO，所以形状不规则）。区域之间要求**边界最小间距 5 格**；长不进去就换方向，仍失败则换中心重试。
- 悬崖每格按"远离区域重心"的方向取 8 个朝向之一（cliff 图集 gid `200+facing`）。
  坦克（LAND）进不去，机甲（OVER_CLIFF）可以通过——这是本地图唯一的兵种不对称。
- 两个出生点对 LAND 单位必须连通，开局用引擎寻路复核；不连通会确定性重试，不带着无效布景开局。

**类型 2 — 固定图**：`evaluate/resource/maps/环湖_单挑.tmx`（111×111）。

两种类型都从图的 5 个指挥中心里选：team0 保留 `a` 个并替换为**机枪机甲**，team1 保留 `b` 个并替换为**猛犸坦克**，
其余中心与所有未选中单位一律删除。隐藏 set 层的 team2 中心运行时不加载。

### 1.2 兵力表

| level | 关系 | 范围 | 例 |
|---|---|---|---|
| 1 | `a = b+1` | `1 ≤ b ≤ 4` | b=2 → 3v2 |
| 2 | `a = b` | `1 ≤ a ≤ 4` | 3v3 |
| 3 | `a+1 = b` | `1 ≤ a ≤ 4` | 3v4 |
| 4 | `a+2 = b` | `1 ≤ a ≤ 3` | 3v5 |

`a`、`b` 各自最大 5，但不会同时为 5（环湖每方只有 5 个中心）。

### 1.3 敌方

内置策略 `rwx.micro.case1.v1`（`meta.enemyPolicy = attackMove+radialRangeRetreat/v1`）：开局向随机锚点收拢、
之后共享攻击移动、低血单位带支援后撤；每 12 tick 决策一次。`enemyDelaySeconds` 可让它延迟启动（只影响布景节奏，不改变胜负判定）。

### 1.4 任务与预算

- 任务 id：`1_<地图类型 1|2>_<level 1|2|3|4>`，例如 `1_2_2`。
- 游戏时间上限 **600 s**（`gameLimitSeconds`）；思考墙钟预算 **1200 s**（`wallLimitSeconds`，从第一条 CLI 请求起算）。
  两者任一达限即 `outcome=timeout`。
- 布景完成后停在 tick 0（briefing，默认 180 s），realtime 与 pause 一样。briefing 期是否允许下令由
  `briefingOrders=allow|deny` 控制（默认 allow）。
- pause 赛道默认一回合 60 tick；注意 60 tick 的引擎时钟只有 **960 game-ms**（`turnSeconds` 是名义秒）。

---

## 2. 快速开始

从仓库根目录：

```powershell
# Agent，pause 赛道（推荐先在无头模式下跑通）
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=pause --data-dir=agent-operation/tmp/micro-a --cli-port-file=agent-operation/tmp/micro-a/port'

# Agent，realtime 赛道
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=realtime --data-dir=agent-operation/tmp/micro-a --cli-port-file=agent-operation/tmp/micro-a/port'

# 开着窗口看同一局（--window 与 --headless 互斥）
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=realtime --data-dir=agent-operation/tmp/micro-a --cli-port-file=agent-operation/tmp/micro-a/port'

# 人类基线（GUI，载入后暂停在 tick 0，按 Q 开局）
.\gradlew.bat :desktop:run '--args=--no-steam --evaluate CASE1-duel --case-args mode=human,task=1_2_2,seed=1'
```

启动成功的标志（stdout）：

```text
RWX_MICRO_RUN <run目录>
RWX_MICRO_READY case=CASE1-duel phase=briefing tick=0
```

端口写在 `--cli-port-file` 指定的文件里。结束时会打印 `RWX_MICRO_RESULT outcome=... reason=...`；
无头模式直接退出进程，窗口/人类模式播完结算动画后按 `RWX_MICRO_EXIT_IN 15..1` 倒计时退出。

### 连接 CLI

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/micro-a/port --stdio
```

整局保持同一个客户端：stderr 等 `ready`，stdin 每行一条命令并 flush，stdout 每次读一行 JSON。
本 CASE 的命令面（哪些是 A/B/C、参数怎么给）见 [commands.md](commands.md)，参与者视角的说明见 [START.md](START.md)。

---

## 3. 完整评测流程

```
跑局 → 解析 replay → 独立复算 → 算指标 → 看报告 → 归档对比
```

**① 跑局**
上一节的命令。产物落在 `evaluate/output/micro/CASE1-duel/<case>_<task>_<track>_s<seed>_<时间戳>/`
（可用 `--case-args ...,out=<目录>` 改根目录）。

**② 解析 replay（自动）**
`evaluate.py` 会调 `evaluate/utils/replay.py` 把 `match.replay` 解析成 `replay/match.jsonl` 等三份视图，
只保留 `meta.controlledTeam` 的我方命令。

**③ 独立复算（re-sim，自动）**
没有 `engine/snapshots.jsonl` 时先跑 re-sim：用 Headless 发行包重放同一份录像，得到 `engine/snapshots.jsonl`、
`engine/events.jsonl`、`engine/final.json`，再与 `result.json` 比对，写 `engine/playback.json`。
需要 Headless 包（`--dist`，默认 `desktop/build/headless`，先 `.\gradlew.bat :desktop:headlessArchive`）和 Java
（`--java` 或 `JAVA_HOME`）。re-sim 在隔离目录里跑，不污染仓库 `replays/`。

**④ 算指标**

```powershell
python -B evaluate/micro/CASE1-duel/evaluate.py evaluate/output/micro/CASE1-duel/<run目录>
```

常用参数：`--resim auto|force|skip`、`--baseline auto|none|<metrics.json>`、`--baseline-index <index.json>`、
`--metrics-config <json>`（只覆盖 `lateMs`/`fireTauMs`）、`--dist`、`--java`。

**⑤ 看报告**

| 文件 | 看什么 |
|---|---|
| `eval/metrics.json` | 全部指标 + `diagnostics` + `mapping` + `evidence`（是否过了复算闸门） |
| `eval/report.txt` | 一屏文本表：指标 × tier × 本轮 × 人类基线 |
| `eval/report.html` | 表格 + 双方 HP/护盾时间线 + B 类命令刻度 + 交战窗口 + pause 回合边界 |

先看 `evidence.source`：`re-sim` 且 `verified=true` 才算过了复算闸门；`live/unverified` 只能当调试数据。

**⑥ 多局汇总（可选）**

```powershell
python -B evaluate/micro/CASE1-duel/report_html.py
```

读取 `evaluate/output/micro/CASE1-duel/<run>/` 已有的 meta/result/gateway/match.replay，为每个 run 生成
`reports/<run>.html`，并生成汇总 `reports/index.html`（评测维度、run 矩阵、时长/请求/原生命令/延迟/结局图表）。
图表是内联 SVG，无外部依赖；`--out` 可改输出目录。

---

## 4. 命令与赛道机制

### 4.1 三个命令类别

| 类 | 含义 | 本 CASE 的命令 |
|---|---|---|
| A | 只读观察 | `rwx-micro-1-state`、`-units`、`-unit`、`-map`、`-path` |
| B | 改变状态 | `-move`、`-attack-move`、`-attack`、`-stop`、`-mode`、`-batch` |
| C | 生命周期/帮助 | `-begin`、`-next`、`-help`、`rwx-ping` |

类别来自命令注册表，不靠命令名前缀判断。未知命令落到 C 并计一次 `rejected`。

### 4.2 赛道

| | realtime | pause |
|---|---|---|
| 时间 | 1× 连续跑 | 回合制：`begin` 跑第 1 回合，`next` 推下一回合 |
| 下令时机 | 随时 | 回合进行中也可下令，命令在下一回合首 tick 生效 |
| `next` | 不存在（发了就是 rejected） | 唯一推进入口 |
| 适合看 | 真实延迟、反应速度 | 决策质量（把"想多久"和"跑多久"解耦） |
| 注意 | 墙钟预算里含真实传输 | tick 间隔 = 回合长度，不是思考速度 |

两个赛道的成绩**不能同榜**。人类只有 realtime 1×。

### 4.3 生命周期

- 布景完成停在 tick 0，`phase=briefing`；`briefingSeconds`（默认 180）内不开始则自动 begin 并记 `lifecycle begin reason=briefing-timeout`。
- `rwx-micro-1-begin` 显式开局（`lifecycle begin reason=command`）。
- pause 每推进一回合写一条 `lifecycle turn`（`reason=boundary`）。
- 结束时写 `lifecycle end` + `result.json`，之后不再接受操作。
- 人类模式按 `Q` 切换暂停。原版暂停键在 `GameUI.updateInput` 里处理，而暂停帧不驱动 `gameLoop`，
  所以它只能暂停、不能恢复；`Q` 走渲染循环，双向都有效。

### 4.4 违规与越权

- 不在白名单的命令、越过本 CASE 授权范围的原生命令（例如直接调通用 order 服务去操控敌方单位）
  一律在执行前拒绝：不进 replay、不改游戏状态、单独写一条违规记录。
- 若发现未经授权的原生命令进入 replay，诊断会标 `severe/native_authority_mismatch`。

---

## 5. 材料与指标

一局跑完能拿到 9 份材料（`meta` / `gateway` / `result` / `match.replay` / `replay/` / `delivery.jsonl` /
`engine` 快照与事件 / `playback.json` / `eval`），每份材料的字段、精度和能回答什么问题，以及 10 个指标的完整定义、
阈值、可比性规则，全部在 **[METRICS.md](METRICS.md)**。

想看某个问题该翻哪里：

| 想知道 | 看 |
|---|---|
| 谁赢了、亏了多少血 | `eval/metrics.json → outcome` |
| 反应快不快 | `ENGAGE_LAG`、`LOWHP_LAG` |
| 手速/节奏 | `cadence`（T1）、`turns`（T3，pause） |
| 有没有在打酱油 | `IDLE_IN_COMBAT`、`FIRE_UPTIME` |
| 是不是在射程外白嫖 | `FREE_FIRE` |
| Agent 想了多久 | `agent_timing.thinkingWallMs`（realtime）、`turns.thinkingWallMs`（pause） |
| 命令到底被执行了没有 | `mapping`（网关请求 ↔ 底层命令）、`evidence` |
| 结果能不能信 | `evidence.source` + `playback.json` |

---

## 6. 人类基线

1. `--evaluate CASE1-duel --case-args mode=human,task=...,seed=...` 启动 GUI，载入后暂停在 tick 0。
2. 人类用鼠标操控 team0，解除暂停后 1× 打完（规则上不暂停/不变速）；自动录像。
3. 结束后 `evaluate.py <run目录>` 照常生成 `eval/metrics.json`（replay-only 路径，无 gateway，T2/T3 为 N/A）。
4. 在 [baselines/index.json](baselines/index.json) 登记：`{"runs": {"<task>:<seed>": "<相对路径或 run 目录>"}}`。
   key 是 **task:seed**，每个要比较的组合都要有自己的人类 run，不能拿别的 seed 顶替。
5. 之后带 `--baseline auto` 的 run 会在报告里并排显示人类 T1 指标；基线只写 `eval/metrics.json.reference`，不回写引擎产物。

**当前状态**：`baselines/index.json` 的 `runs` 是空的——还没有登记过人类基线。
`evaluate/output/micro/CASE1-duel/` 下存在过一次 `mode=human` 的 run（`1_1_2`，outcome=win），
说明这条路径能跑通并写出 meta/result/replay；但它**没有登记**，也没有经过"人工监督 1× 不暂停"的正式采集。
`mode=human` 只把"解除暂停"当开局，**没有强制禁用 GUI 的暂停/变速**，鼠标操控与胜负展示的体验仍需人工确认。

---

## 7. 复现与清理

- 同 `task+seed` 布景确定：地图、单位、位置、朝向、初始 HP 一致。目录名、启动耗时、realtime 请求到达的 tick 不属于确定性布景。
- 反复重跑同一配置会在 `evaluate/output/micro/` 留下只差时间戳的兄弟目录，根目录 `replays/` 也会堆积。
  整理用 [`tools/cleanup_micro_output.py`](../../tools/README.md)：先不带参数看计划，确认后加 `--apply`。
- 临时文件放 `agent-tmp/`；联机/评测实例的端口文件放 `agent-operation/tmp/`。

---

## 8. 目录

```
evaluate/micro/CASE1-duel/
  README.md            本文件：运行、场景、流程、机制
  METRICS.md           材料清单 + 指标定义 + 阈值 + 局限
  START.md             参与者说明（只写对局所需内容）
  commands.md          命令手册（会打进 core jar，供 rwx-micro-1-help 读取）
  prompt.md            Agent 任务提示词
  evaluate.py          离线评测入口
  report_html.py       多局汇总 HTML
  metrics/             10 个指标插件（outcome/cadence/ENGAGE_LAG/…/turns）
  baselines/index.json 人类基线登记表
```
