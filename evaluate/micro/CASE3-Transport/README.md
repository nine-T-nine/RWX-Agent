# CASE3-Transport — 军事运输

从右下角基地岛生产指定陆军，用运输机跨岩浆送到左上角目标岛。首次满足目标岛的指定陆军组成即成功，
运输机全部阵亡即失败。考察的是**任务工期估算与并行调度**：生产、装载、航行、卸载之间的依赖与重叠。

本文件只讲**怎么跑、场景怎么布、材料在哪**。指标口径与材料字段见 [METRICS.md](METRICS.md)。
参与者只需要 [START.md](START.md) 与 [command.md](command.md)。

---

## 1. 场景布置

### 1.1 地图

`130 × 130` 格，20 世界单位/格。初始全图为岩浆，再生成两个彼此分离、内部连通的草地区域：

- 左上角**目标岛**：550~675 格（按格数算，不是世界坐标面积）；圆心约 `(22,22)`。
- 右下角**基地岛**：700~800 格；圆心约 `(106,106)`。

两座岛都用"圆形底 + 边缘噪声"生成：按角度采样一维分形值噪声（3 个八度、周期闭合），
半径乘 `1 + 0.22 × noise`，再对半径做二分使格数落进面积区间。形状是**星形**，天然连通、无单格颈部。
同一个 `(task, seed)` 的地形完全一致。

### 1.2 布景顺序

1. **先放提取器**：在基地岛上按 3 格网格铺 `extractorT3_overclocked` × 5（type 4 为 10），跳过工厂脚印。
2. **再放工厂**：围绕基地岛包围盒中心，每行 2 座（横向间隔 4 格、行间 12 格 ≥ 要求的 10 格）。
3. **最后放运输机**：在基地岛边缘（四邻接 < 4 的格）中排除已占用格，按 y 降序/x 降序排序后确定性洗牌取前 N 个。
4. **目标岛放防空塔**（仅 type 1~3）：按 BFS 求每格到海岸的距离，取 `3~6` 格的环带；
   把 360° 均分为 4 个扇区，每扇区在该带内各取 1 个点，点间中心距 ≥ 8 格（放不下才逐格放宽）；
   最后叠加 ±0.35 格的亚格抖动。`meta.antiAirTurrets[].coastDepth` 记录实际离岸深度。
5. 每座岛、工厂、提取器、运输出生点、卸载区都在生成后用引擎复核合法性，不合法直接失败，不带着无效布景开局。

**迷雾**：一次性揭示——开局把各队 fog 数据填 0（0 = 当前可见），并关闭周期性维护，所以全图保持已揭示。

### 1.3 兵力表

任务 id：`3_<type 1..4>_<level 1..3>`，`level` 是目标数量的倍率。

| type | 目标岛所需陆军（×level） | 运输机 | 经济建筑 | 工厂 | 初始资金 | 游戏上限 |
|---|---|---:|---:|---|---:|---:|
| 1 | 重型坦克 3、等离子坦克 2、自行火炮 3 | 运输机 ×3 | 超频提取器 ×5 | T2 陆军工厂 ×2 | 2,000 | 15 min |
| 2 | 机枪机甲 1、火炮机甲 2 | 运输机 ×3 | 超频提取器 ×5 | T2 机甲工厂 ×2 | 4,000 | 20 min |
| 3 | 喷火机甲 1、特斯拉机甲 1、猛犸坦克 2、重型坦克 3 | 运输机 ×3 | 超频提取器 ×5 | T2 机甲工厂 ×2、T2 陆军工厂 ×2 | 4,000 | 25 min |
| 4 | 实验型悬浮坦克 2、实验型概念坦克 1 | 飞行堡垒 ×2 | 超频提取器 ×10 | 实验工厂 ×2 | 20,000 | 30 min |

内部 ID：`heavyTank`、`plasmaTank`、`c_artillery`、`mechMinigun`、`mechArtillery`、`mechFlame`、
`mechLightning`、`c_mammothTank`、`experimentalHoverTank`、`c_experimentalTank`。

> **注意**：type 4 没有防空塔（草稿未指定），所以 `anti_air_exposure` 在该 type 上为 N/A。

### 1.4 关键机制数值（已按源码核实）

| 项 | 值 | 说明 |
|---|---|---|
| 运输机 `dropship` | 容量 **4**、价格 800 | 内置 Java 单位 |
| 飞行堡垒 `experimentalDropship` | 容量 **12**、HP 8000 | INI 定义 |
| 席位权重 | `mechFlame`/`mechLightning` **2**、`experimentalTank` **5**、其余 **1** | `BaseUnit.getTransportSlotsNeeded()`——**不是**每单位 1 席 |
| 装载 | 原生命令 `UnitCommandType.loadInto`（地面单位 → 运输机） | CLI 封装为 `rwx-micro-3-load` |
| 卸载 | 运输机的 unload **action**（dropship 109 号）：开启后停在非液体地形**每 30 帧卸一个** | `rwx-micro-3-unload` 走 `setActionId`，不是伪造命令 |
| 防空塔 `c_antiAirTurret` | HP 800、射程 **250** 世界单位（12.5 格）、`shootDelay` 80 帧、弹丸 60 伤害、**只打空中目标** | 4 发即让 dropship（HP 500）损失过半 |
| 目标判定 | 目标岛格集合内、**存活、未装载、team 0** 的陆军逐类型达标 | 在货舱里 / 基地岛 / 岩浆上 / 已阵亡都不算 |

---

## 2. 快速开始

从仓库根目录：

```powershell
# 无头
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE3-Transport --case-args task=3_1_1,seed=1,track=realtime --data-dir=agent-operation/tmp/transport-a --cli-port-file=agent-operation/tmp/transport-a/port'

# 带窗口观看
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE3-Transport --case-args task=3_1_1,seed=1,track=realtime --data-dir=agent-operation/tmp/transport-a --cli-port-file=agent-operation/tmp/transport-a/port'
```

启动成功：

```text
RWX_MICRO_RUN <run目录>
RWX_MICRO_READY case=CASE3-Transport phase=briefing tick=0
```

本 CASE **只接受 `mode=agent,track=realtime`**：`mode=human`、`track=pause`、`turnTicks`、`turnSeconds`、
`enemyDelaySeconds` 都会在启动时被拒绝。

> **没有 `begin`**：本 CASE 是 `startsRunning()`，布景完成即 1× 连续运行。进入游戏时会自动执行并输出一次初始状态观察；
> 目标配置只在进入对局后通过 `rwx-micro-3-goal` 公开，不在启动前泄露。

### 连接 CLI

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/transport-a/port --stdio
```

命令面见 [command.md](command.md)。要点：**`load` 回执不代表单位已上船**，真实装载要等货舱变化。

---

## 3. 完整评测流程

**① 跑局** — 产物落在 `evaluate/output/micro/CASE3-Transport/<case>_<task>_realtime_s<seed>_<时间戳>/`。

**② 观察与操作** — 观察用 `rwx-micro-3-state/goal/production/fleet` 和只读的 `rwx-game-map-show`、
`rwx-game-unit-list/unit-show`；操作用 `produce` / `load` / `move` / `unload`。

**③ 离线评测**

```powershell
python evaluate/micro/CASE3-Transport/evaluate.py evaluate/output/micro/CASE3-Transport/<run目录>
# 也可以传父目录：会遍历其中所有含 meta.json 的子目录
# 加 --json 打印完整 metrics 文档
```

输出 `eval/metrics.json`、`report.txt`、`report.html`。本 CASE **不做 re-sim**：生产/运输/目标证据来自 live 引擎记录，
`match.replay` 只用于核对底层命令。

**④ 看结果**

| 文件 | 看什么 |
|---|---|
| `eval/metrics.json` | 9 个指标 + `diagnostics` + `evidence` |
| `engine/goal.jsonl` | 逐类型送达进度与首次达成 |
| `engine/transport.jsonl` | 装载/货舱/移动/卸载/受伤的真实序列 |
| `engine/production.jsonl` | 订单受理 → 开工 → 完成里程碑 |

无参数运行时会打印一行摘要：`task seed: outcome (reason) game=…ms delivered=… rate=… hits=… hpLost=… loads=… emptyMoves=… violations=…`，
并逐个类型列出 `delivered/required` 与首末送达时刻。

**⑤ 冒烟/回归**（需要先构建 Headless 包：`.\gradlew.bat :desktop:headlessArchive`）

```powershell
python -B evaluate/micro/CASE3-Transport/test/smoke.py --dist desktop/build/headless
# --heavy 追加实验运输（type 4）；--tmp 改临时目录；--java 指定解释器
```

覆盖：基线取胜、缩短预算 → timeout、无 Agent → error、越权命令被拒（含 `rwx-game-order-*` 与 `rwx-micro-2-*`）、
同 `task+seed` 布景可复现。

---

## 4. 机制

### 4.1 生命周期

- 布景完成即 1× 运行，没有 `begin`/`pause`/`step`/`speed`。
- 结束时：有窗口播原版结算动画，无窗口直接打印；两条路径都打印 `RWX_MICRO_EXIT_IN 10..1` 后退出。
- `success` = 目标岛首次满足；`defeat` = 运输机全灭；`timeout` = 预算先到；`error` = 场景/运行异常。
- 违规**不改变胜负**。

### 4.2 命令类别

| 类 | 命令 |
|---|---|
| A | `rwx-micro-3-state`、`-goal`、`-production`、`-fleet`、`rwx-game-map-show`、`rwx-game-map-path`、`rwx-game-unit-list`、`rwx-game-unit-show` |
| B | `-produce`、`-load`、`-move`、`-unload` |
| C | `-help`、`rwx-ping` |

生产走 `QueueUnitAction.getActionId()` + `Command.setActionId`；`--count N` 发 N 条原生命令。

### 4.3 限制与拒绝

- 只允许生产目标清单里的陆军：不允许生产多余单位、建造建筑、升级工厂、操控非本局单位。
- 运输机只能执行合法装载/移动/卸载；陆军不能直接穿岩浆。
- 违规请求在执行前拒绝、单独记 `violations.jsonl`、不进 replay、不改状态。普通参数错误与明确违规分开记录。

---

## 5. 材料与指标

一局跑完能拿到 9 类材料：`meta` / `gateway` / `result` / `match.replay`+`replay/` / `delivery.jsonl` /
`engine/production.jsonl` / `engine/transport.jsonl` / `engine/goal.jsonl` / `violations.jsonl`（另有 `eval/` 输出）。
每份材料的字段、精度与坑，以及 9 个指标的完整定义，全部在 **[METRICS.md](METRICS.md)**。

| 想知道 | 看 |
|---|---|
| 赢没赢、多快 | `outcome.goalReachedGameMs` |
| 生产堵没堵 | `production_lifecycle.queueWait`、`perFactory` |
| 运力用满了没有 | `transport_utilization.fillRate`、`emptyMoves` |
| 单位真的到了吗 | `delivery.units[]` + `engine/goal.jsonl` |
| 在哪里挨打 | `transport_damage` + `anti_air_exposure` |
| 生产和运输重叠了多久 | `mission_phases.productionOverlapMs` |
| Agent 观察是否滞后 | `ab_timing` + `gateway.jsonl` |

---

## 6. 基线

每个 type 一个入口：`baseline/type1.py`、`type2.py`、`type3.py`、`type4.py`（共用 `baseline/common.py`）。
脚本接收 level/seed 对应的实例端口文件，通过与 Agent 相同的公开命令交互；**不读** `meta.json` 私有字段、
EngineEvidence、Replay 或其他 run。

策略（确定性）：

1. **卸载点**：开局用一次 `rwx-game-map-show --x 370 --y 370 --half-width 20 --half-height 20` 取目标岛陆地格，
   用 `rwx-game-unit-list --owner enemy --json` 取可见防空塔位置；在"四邻都是陆地"的格子里选**离最近炮塔最远**的一格
   （并列取离基地最近）。取不到陆地时退回目标岛中心 `(370,370)`。
2. **生产**：每个空闲工厂接第一个"目标缺口 > 0"的类型；已下单未出现的计入 in-flight，避免超产。
3. **装载**：id 最小的有空位运输机装 id 最小的空闲目标单位；每条装载请求按运输机记忆，
   直到单位真正进入货舱或 20 秒超时，避免重复下单或提前起飞。
4. **起飞条件**：货舱满（`freeSlots == 0`）**或** 全部目标单位都已生产且没有待装单位——一批一次航程，
   而不是"装满一个就走"的多次暴露；已在航行中的运输机不会重复下单。
5. **卸载与返航**：进入卸载点 140 世界单位内即发卸载动作，卸完立刻返回基地岛（离开防空射程）。
6. 每 2 秒轮询一次公开状态；不用固定等待、不读隐藏数据。

参考成绩（`seed=1`、`level=1`、基线策略、全部运输机存活、零违规）：

| task | 结果 | 达标 gameMs | 送达 |
|---|---|---:|---|
| `3_1_1` | success | 87,168 | heavyTank 3/3、plasmaTank 2/2、c_artillery 3/3 |
| `3_2_1` | success | 82,880 | mechMinigun 1/1、mechArtillery 2/2 |
| `3_3_1` | success | 165,184 | mechFlame 1/1、mechLightning 1/1、c_mammothTank 2/2、heavyTank 3/3 |

这是同一策略的历史实测值，用来判断"一局跑通大概长什么样"，**不是**通过线。

---

## 7. 复现与清理

- 同 `task+seed` 布景确定；`seed` 只影响地形、矿脉位置、运输机散布与炮塔落点。
- 反复重跑会在 `evaluate/output/micro/` 留下只差时间戳的兄弟目录。
  整理用 [`tools/cleanup_micro_output.py`](../../tools/README.md)：先不带参数看计划，确认后加 `--apply`。
- 临时文件放 `agent-tmp/`，端口文件放 `agent-operation/tmp/`。

---

## 8. 目录

```
evaluate/micro/CASE3-Transport/
  README.md            本文件：运行、场景、流程、机制
  METRICS.md           材料清单 + 指标定义 + 阈值 + 已知问题
  START.md             参与者说明
  command.md           命令手册（会打进 core jar）
  prompt.txt           Agent 任务提示词
  evaluate.py          离线评测入口
  metrics/             9 个指标插件（outcome/production_lifecycle/delivery/…）
  baseline/            type1-4 基线脚本 + common.py
  test/                smoke.py 端到端冒烟
```
