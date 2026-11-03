# CASE2-LTE — Limited-Time Economy

控制唯一建造者，在矿脉上建造并逐级升级提取器，让**真实引擎的** `incomePerSecond` 达到目标值。
没有敌人、没有额外建造者、没有补贴、没有战斗、没有自动补发。考察的是**经济工程的工期估算与并行调度**：
先建哪个点、什么时候升级、资金流怎么排。

本文件只讲**怎么跑、场景怎么布、材料在哪**。指标口径、材料字段、引擎事实核验见 [METRICS.md](METRICS.md)。
参与者只需要 [START.md](START.md) 与 [commands.md](commands.md)。

---

## 0. 交付给被测 Agent 的文件

**只交这两份**：

| 文件 | 内容 |
|---|---|
| [START.md](START.md) | 任务、公开规则、参与流程、参与边界 |
| [commands.md](commands.md) | 完整命令接口（会打进 core jar，供 `rwx-micro-2-help` 逐节读取） |

**本目录其余文件都是管理员/开发者资料，不得出现在 Agent 可见的文件列表里**：

| 文件 | 为什么不给 |
|---|---|
| `README.md`（本文件） | 含地图尺寸推算、矿脉采样、建造者参数、经济表细节与基线策略 |
| `METRICS.md` | 含材料字段、指标阈值、`incomePerSecond` 真实换算与扣分口径 |
| `prompt.txt` | 本 CASE 的实现提示词（含验收清单与源码勘察要求） |
| `score_design.md` | 扣分规则草稿 |
| `evaluate.py`、`score.py`、`greedy.py`、`metrics/`、`test/` | 评测实现、贪心基线与单测 |

路由：

- **只测本 CASE** → 只给 `START.md` + `commands.md`。
- **一次测全部四个 CASE** → 先给 [`../EVALUATION.md`](../EVALUATION.md)，再给这两份。

---

## 1. 场景布置

### 1.1 任务

任务 id：`2_<矿脉数 Y>_<经济档次 T>`，`Y=4..8`、`T=1..5`，例如 `2_6_3`。初始资金固定 **2100**。

| 档次 | 目标组成 | 目标收入 X（Y 个矿脉） |
|---|---|---|
| 1 | Y×T1 | 8Y |
| 2 | ⌊Y/2⌋×T1 + ⌈Y/2⌉×T2 | 8·⌊Y/2⌋ + 12·⌈Y/2⌉ |
| 3 | Y×T2 | 12Y |
| 4 | ⌊Y/2⌋×T2 + ⌈Y/2⌉×T3 | 12·⌊Y/2⌋ + 20·⌈Y/2⌉ |
| 5 | Y×T3 | 20Y |

各 Y 的实际目标值（来自加载的 8/12/20）：

| Y | T1 | T2 | T3 | T4 | T5 |
|---:|---:|---:|---:|---:|---:|
| 4 | 32 | 40 | 48 | 64 | 80 |
| 5 | 40 | 52 | 60 | 84 | 100 |
| 6 | 48 | 60 | 72 | 96 | 120 |
| 7 | 56 | 72 | 84 | 116 | 140 |
| 8 | 64 | 80 | 96 | 128 | 160 |

固定 task 的资金、数量和目标**不随 seed 变化**。

### 1.2 地图

边长 `a` 格的正方形草地（20 世界单位/格）。`a` 由泊松盘采样的经典密度推算：

```
a = ceil(sqrt(Y·r²/0.7 + π·15²))     r = 20 格（矿脉最小间距），15 格 = 建造者中心净空半径
```

即 Y=4..8 对应 `a = 55/60/65/69/73`。

### 1.3 矿脉

Bridson 泊松盘采样（最小间距 20 格），剔除建造者半径 15 格内的点，再用 seed 洗牌取前 Y 个，
按 `(y,x)` 排序为 `R1..RY`。重采样只改变采样种子，不改变 `a`。

矿脉格写进 TMX 的 `Items` 层，用与 `assets/maps/skirmish` 相同的资源池定义：`misc` tileset（`bitmaps/misc.png`）
第 4 号块带 `res_pool` 属性——所以显示的是**原版资源池贴图**，不是自制图标。

### 1.4 建造者

唯一 `builder`，ID=1000，出生在地图正中心：HP 170，草地速度 0.8 世界单位/tick，加速 0.04/tick，施工预热 30 tick。
原版可建造列表仍存在，但本 Case **只开放 `b_extractorT1`**。

### 1.5 经济

| 等级 | 内部 ID | `incomePerSecond` | 直接建造费用 / 工作 tick | 下一标准动作 | 升级费用 / 工作 tick |
|---|---|---|---:|---|---:|
| T1 | `extractorT1` | 8 | 700 / 1000 | `extractorT2_0` | 1400 / 1666.667 |
| T2 | `extractorT2` | 12 | — | `extractorT3_0` | 4000 / 3333.333 |
| T3 | `extractorT3` | 20 | — | — | — |

- 建造动作是 `b_extractorT1`。T3 的超频（`extractorT3_overclocked_0`）、护盾（`extractorT3_reinforced_1`）
  及这些分支的降级动作**全部禁止**。
- 表来自当前加载的自定义单位，**不是**旧 Java `ResourceExtractor` 的 8/12/18、1200/2500 或 102/103。
  工作 tick 是进度速率的倒数，不含命令排队、寻路、转向、进入施工距离和 30 tick 预热。
- INI 里 T2/T3 的 `price=2100/6100` 是**累计价值**，不是可直接建造的价格。
- `incomePerSecond` 是标称值：资源每 **40 tick** 发放一次，实际约 12/18/30 credits 每墙钟秒。
  完整说明见 [METRICS.md](METRICS.md) §4.4。

### 1.6 布景校验

开局用引擎检查：每个点放置是否合法、LAND 可到达（矿脉四邻接至少有一个可通行格与出生点连通）、
初始资金能启动、初始收入低于目标、`Y × T3 收入` 可达目标；单位/建筑清空后只生成一个建造者，未使用的玩家槽位移除。
同 `task+seed` 的地图内容和初始状态确定；目录、启动耗时和 realtime 请求到达 tick 不属于确定性布景。

---

## 2. 快速开始

从仓库根目录：

```powershell
# 无头
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE2-LTE --case-args task=2_5_3,seed=1,track=realtime --data-dir=agent-operation/tmp/lte-a --cli-port-file=agent-operation/tmp/lte-a/port'

# 带窗口观看同一局（结算动画后等 10 秒退出）
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE2-LTE --case-args task=2_5_3,seed=1,track=realtime --data-dir=agent-operation/tmp/lte-a --cli-port-file=agent-operation/tmp/lte-a/port'
```

启动成功：

```text
RWX_MICRO_RUN <run目录>
RWX_MICRO_READY case=CASE2-LTE phase=briefing tick=0
```

本 CASE **只接受 `mode=agent,track=realtime`**：给 `mode=human`、`track=pause`、`turnTicks`、
`turnSeconds` 或 `enemyDelaySeconds` 都会在启动时被拒绝。

### 连接 CLI

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/lte-a/port --stdio
```

briefing 阶段只观察，必须显式发 `rwx-micro-2-begin` 才会开始计时。命令面见 [commands.md](commands.md)。

---

## 3. 完整评测流程

**① 跑局** — 上一节的命令。产物落在 `evaluate/output/micro/CASE2-LTE/<case>_<task>_realtime_s<seed>_<时间戳>/`。

**② 观察与操作** — A 类看（`state`/`builder`/`resources`），B 类做（`build`/`upgrade`），
C 类生命周期（`begin`/`help`/`ping`）。

**③ 离线评测**

```powershell
python evaluate/micro/CASE2-LTE/evaluate.py evaluate/output/micro/CASE2-LTE/<run目录>
```

输出 `eval/metrics.json`、`report.txt`、`report.html`（后两者是同一份 JSON 的文本/HTML 包装）。
`metrics.json` 里除了事实指标还有独立的 `score` 扣分文档（上限 100 − 三项封顶扣分，口径见
[METRICS.md §14](METRICS.md)）。本 CASE **不做 re-sim**：生命周期来自 live 引擎记录，replay 只用于核对底层命令。

**④ 看结果**

| 文件 | 看什么 |
|---|---|
| `eval/metrics.json` | 7 个指标 + 顶层 `score`（扣分）+ `diagnostics` + `evidence`（`independentResimulation` 恒为 false） |
| `result.json` | authoritative 结局与 `targetReachedGameMs` |
| `engine/lifecycle.jsonl` | 每条指令的阶段流水、native 关联、收入锚点 |

先看 `outcome`，再看 `instruction_lifecycle.result` 与 `wake_alignment`。
指标细节全部在 [METRICS.md](METRICS.md)。

**⑤ 回归测试**

```powershell
# 纯 Python 单测，不需要引擎
python -B -m unittest discover -s evaluate/micro/CASE2-LTE/test -p test_lte.py

# 端到端冒烟（需要先构建 Headless 包：.\gradlew.bat :desktop:headlessArchive）
python -B evaluate/micro/CASE2-LTE/test/smoke.py --dist desktop/build/headless

# 批量跑 Greedy 并输出摘要
python -B evaluate/micro/CASE2-LTE/test/run_configs.py --dist desktop/build/headless 2_4_1 2_5_2 2_6_3
```

---

## 4. 机制

### 4.1 生命周期

- 布景完成后停在 tick 0（briefing）。**不会自动开始**，必须显式 `rwx-micro-2-begin`。
- 第一版只允许 realtime 1×；没有 pause/step/speed。
- `beginWallMs` 是任务时钟的起点；`gameElapsedMs`/`wallElapsedMs` 都从它算。
- 结算：有窗口播原版结算动画，无窗口直接打印；两条路径都打印 `RWX_MICRO_EXIT_IN 10..1` 再退出。

### 4.2 命令类别

| 类 | 命令 |
|---|---|
| A | `rwx-micro-2-state`、`-builder`、`-resources` |
| B | `-build`、`-upgrade` |
| C | `-begin`、`-help`、`rwx-ping` |

**没有全游戏观察命令**（`rwx-game-*` 全部不可用），这是与 CASE3/CASE4 的差别：本 CASE 只让 Agent 看经济面。

### 4.3 指令替换与失败语义

- 新建命令**替换**建造者尚未完成的旧订单，旧指令记为 `superseded`；已生成的未完工地基保留，**没有自动恢复施工**。
- 重复占用矿脉、忙碌建筑不能提交。
- 标准费用全额预付：建造在创建地基前扣 700，资金不足由原生订单重试；升级在原生命令执行时扣款，
  提交前买不起则 `invalid_request: insufficient_credits`——**Case 不创建代付/待发队列**。
- 升级不支持取消、跳级、护盾、超频或降级。
- 不同建筑升级可并行，也可与建造者移动/施工并行；建筑自主升级，不需要建造者到场。

### 4.4 违规

不在白名单的命令、越权原生命令一律执行前拒绝：不进 replay、不改状态、单独写 `violations.jsonl`。
若发现未经授权的原生命令进入 replay，诊断标 `severe/native_authority_mismatch`。

---

## 5. 材料与指标

一局跑完能拿到 8 类材料：`meta` / `gateway` / `result` / `match.replay`+`replay/` / `delivery.jsonl` /
`engine/lifecycle.jsonl` / `violations.jsonl` / `eval/`。每份材料的字段与精度，以及 7 个指标的完整定义
（含 `instruction_lifecycle` 的匹配规则、`wake_alignment` 的陷阱、`incomePerSecond` 的真实口径），
全部在 **[METRICS.md](METRICS.md)**。

| 想知道 | 看 |
|---|---|
| 这次跑得怎么样（扣分） | `eval/metrics.json.score.total` / `score.items` |
| 达标了吗、多快达标 | `outcome.targetReachedGameMs` / `gameElapsedMs` |
| 收入怎么涨起来的 | `income_anchors` |
| 哪条指令慢、慢在哪个阶段 | `instruction_lifecycle.phases` |
| 指令到底执行成功没有 | `instruction_lifecycle.result` + `mapping` |
| Agent 收到完成消息有多滞后 | `wake_alignment` |
| 命令是不是被拒了 | `agent_timing.errorRatio` + `violations` |

---

## 6. Greedy 基线

```powershell
python evaluate/micro/CASE2-LTE/greedy.py --port-file agent-operation/tmp/lte-a/port
```

只通过持久 CLI 读三类公开观察，**不读证据、不读 `meta.json` 私有字段**。

- **候选排序**：`score = Δincome / (fundingWait + travel + warmup + work)`；
  `fundingWait = max(0,cost−credits)/(income×60/40)`（收入为 0 且资金不足时为无穷），
  建造 `travel = pathDistance/(0.8×60)`、`warmup = 30/60`、`work = 1000/60`，升级 `travel/warmup = 0`、
  `work = 升级工作tick/60`。按 score 降序、预计时间升序、build 优先于 upgrade、稳定数值 ID 排序。
- **距离**：从建造者位置出发的既有 `AiMapGraph` 四邻接静态 LAND BFS，取矿脉四邻接可通行格最小值 ×20
  （矿脉格本身对 LAND 不可通行；忽略动态建筑与软碰撞，**不声称精确行驶路线**）。
- **并行**：建造者忙时不提交新建，但可向空闲建筑升级；升级候选会为建造者尚未支付的建造费用预留资金；
  不能同一建筑排队多次；独立且买得起的候选可连续提交。
- **睡眠**：取"下一候选的预计可支付时刻"与"所有在途指令剩余完成时刻"的最小值 +2 个名义 tick；
  醒来后按真实剩余进度重算，不固定周期轮询。

它是一阶贪心估算，**不保证最优**，也不保证完成 tick 完全相同。

---

## 7. 复现与清理

- 同 `task+seed` 布景确定；`task` 固定资金/数量/目标，`seed` 只影响地图与矿脉位置。
- 反复重跑同一配置会在 `evaluate/output/micro/` 留下只差时间戳的兄弟目录。
  整理用 [`tools/cleanup_micro_output.py`](../../tools/README.md)：先不带参数看计划，确认后加 `--apply`。
- 临时文件放 `agent-tmp/`，端口文件放 `agent-operation/tmp/`。

---

## 8. 目录

```
evaluate/micro/CASE2-LTE/
  README.md            本文件：运行、场景、流程、机制
  METRICS.md           材料清单 + 指标定义 + 引擎事实核验
  START.md             参与者说明
  commands.md          命令手册（会打进 core jar）
  prompt.txt           Agent 任务提示词
  evaluate.py          离线评测入口
  score.py             扣分评分（case2-score-v1，写进 metrics.json 的 score）
  greedy.py            Greedy 基线
  metrics/             7 个指标插件（outcome/income_anchors/instruction_lifecycle/…）
  test/                test_lte.py（单测）、smoke.py（冒烟）、run_configs.py（批量跑）
```
