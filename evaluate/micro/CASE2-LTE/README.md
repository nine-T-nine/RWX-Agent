# CASE2-LTE — Limited-Time Economy

控制唯一建造者，在矿脉上建造、逐级升级提取器，使真实引擎 `incomePerSecond >= targetIncomePerSecond`。仅 realtime；无敌人、额外建造者、补贴、战斗、自动补发和总分。参与者只需 [START.md](START.md) 与 [commands.md](commands.md)。

## 1. 启动与场景

```powershell
# 无头
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE2-LTE --case-args task=2_5_3,seed=1,track=realtime --data-dir=agent-operation/tmp/lte-a --cli-port-file=agent-operation/tmp/lte-a/port'
# 带窗口观看（同一局，结算动画后等待 10 秒退出）
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE2-LTE --case-args task=2_5_3,seed=1,track=realtime --data-dir=agent-operation/tmp/lte-a --cli-port-file=agent-operation/tmp/lte-a/port'
```

任务 ID 为 `2_<矿脉数 Y>_<经济档次 T>`，Y=4..8、T=1..5，例如 `2_6_3`。初始资金固定 2100。

| 档次 | 目标组成 | 目标收入 X（Y 个矿脉） |
|---|---|---|
| 1 | Y×T1 | 8Y |
| 2 | floor(Y/2)×T1 + ceil(Y/2)×T2 | 8·floor(Y/2) + 12·ceil(Y/2) |
| 3 | Y×T2 | 12Y |
| 4 | floor(Y/2)×T2 + ceil(Y/2)×T3 | 12·floor(Y/2) + 20·ceil(Y/2) |
| 5 | Y×T3 | 20Y |

各 Y 的实际目标值（来自加载的 8/12/20）：

| Y | T1 | T2 | T3 | T4 | T5 |
|---:|---:|---:|---:|---:|---:|
| 4 | 32 | 40 | 48 | 64 | 80 |
| 5 | 40 | 52 | 60 | 84 | 100 |
| 6 | 48 | 60 | 72 | 96 | 120 |
| 7 | 56 | 72 | 84 | 116 | 140 |
| 8 | 64 | 80 | 96 | 128 | 160 |

地图是边长 `a` 格的正方形草地（20 世界单位/格）。`a` 由泊松盘采样经典密度推算：密度约 `0.7 / r²`（r=20 格最小间距），并把建造者中心净空区补回可放置面积，`a = ceil(sqrt(Y·r²/0.7 + π·15²))`，即 Y=4..8 对应 a=55/60/65/69/73。矿脉用 Bridson 泊松盘采样生成（最小间距 20 格），剔除建造者半径 15 格内的点，再用 seed 洗牌取前 Y 个并按 (y,x) 排序为 `R1..RY`。重采样只改变采样种子，不改变 `a`。矿脉格写入 TMX 的 `Items` 层，使用与 `assets/maps/skirmish` 相同的资源池定义：`misc` tileset（`bitmaps/misc.png`）第 4 号块带 `res_pool` 属性，因此显示的是原版资源池贴图。

唯一 `builder` ID=1000，出生在地图正中心，HP=170，草地速度 0.8 世界单位/tick，加速 0.04/tick、施工预热 30 tick。原版可建造列表仍存在，但 Case 只开放 `b_extractorT1`。初始收入 0。固定 task 的资金、数量和目标不随 seed 变化。

开局用引擎检查每点放置是否合法、LAND 可到达（矿脉四邻接至少有一个可通行格与出生点连通）、初始资金能启动、初始收入低于目标、资源数×T3收入可达目标；单位/建筑清空后只生成一个建造者，未使用玩家槽位移除。同 task+seed 的地图内容和初始状态确定；目录、启动耗时和 realtime 请求到达 tick 不属于确定性布景。

## 2. 经济与并行

| 等级 | 内部 ID | 引擎 `incomePerSecond` | 直接建造费用 / 工作 tick | 下一标准动作 ID | 升级费用 / 工作 tick |
|---|---|---|---|---|---|
| T1 | `extractorT1` | 8 | 700 / 1000 | `extractorT2_0` | 1400 / 1÷0.0006 ≈1666.667 |
| T2 | `extractorT2` | 12 | N/A | `extractorT3_0` | 4000 / 1÷0.0003 ≈3333.333 |
| T3 | `extractorT3` | 20 | N/A | 无 | N/A |

建造动作是 `b_extractorT1`。T3 的 `extractorT3_overclocked_0`（超频）、`extractorT3_reinforced_1`（护盾）以及这些分支的降级动作全部禁止。表来自当前加载的自定义单位，不使用旧 Java `ResourceExtractor` 的 8/12/18、1200/2500 或 102/103。

工作 tick 是进度速率的倒数，不含命令排队、寻路、转向、进入施工距离及建造者 30 tick 预热；浮点累计、离散 tick 会产生取整差，实际完成时刻以引擎证据为准。T2/T3 的 INI `price=2100/6100` 是累计价值，不能当成可直接建造的价格。

`incomePerSecond` 与现有游戏 CLI 一致：`team.b(Resource.D)-team.getResourceDrainRate(Resource.D)`。默认资源生成周期是 40 tick，因此标称 8/12/20 分别每 40 tick 发放，1× 名义每墙钟秒 60 tick，资金积累约为 12/18/30 credits/墙钟秒；按 `gameMs` 则是 12.5/18.75/31.25 credits/游戏秒。不要把字段名理解为实际每 1000 game-ms 入账量。

建造者负责寻路、放置和施工；建筑自主升级，无需建造者。不同建筑升级可并行，也可与建造者移动/施工并行。标准费用全额预付：建造在创建地基前扣 700，资金不足由原生订单重试；升级在原生命令执行时扣款，提交前买不起则 `invalid_request: insufficient_credits`，Case 不创建代付/待发队列。收入从完工或转为目标类型时生效，入账仍按原生周期。

新建命令替换建造者尚未完成的旧订单，旧指令记为 `superseded`；已生成的未完工地基保留，没有自动恢复施工。重复占用矿脉、忙碌建筑不能提交。升级不支持取消、跳级、护盾、超频或降级。

## 3. 预算与产物

游戏上限 20 分钟（1200 game-s）、墙钟预算 1320s（首条 CLI 请求开始，留出 1× 追平 20 分钟游戏时间的余量）、无 Agent 接入上限 600s（ready 起算）。可用 `gameLimitSeconds/wallLimitSeconds/startTimeoutSeconds` 覆盖。briefing 只观察，必须显式 `begin`，不会自动开始。第一版只允许 `mode=agent,track=realtime`；不接受 turn 参数或敌方参数。

时间到或达到目标后：有窗口时播放原版胜利/失败结算动画，无窗口直接打印结果；两条路径都会打印 `RWX_MICRO_EXIT_IN 10..1` 并在 10 秒后退出。

每 tick 的 `gameMs` 实际 +16；1× 名义 60 tick/墙钟秒。所有原始 `wallMs` 共享 `System.nanoTime` 的 run 初始化原点（包含载入时间）；首条 CLI 的预算原点和 `beginWallMs` 另记。完成墙钟耗时从 begin 计算。`success/timeout/error` 由引擎决定，违规不修改胜负。

复用 `MicroCaseRegistry/MicroRuntime/MicroRunWriter` 的隔离目录、Gateway 和自动 Replay。每局写 `meta.json`、`gateway.jsonl`、`match.replay`、`engine/lifecycle.jsonl`、`violations.jsonl`、`result.json`。meta 固定完整经济表、允许/禁止动作、矿脉、建造者、地图哈希、地图边长/间距/净空和预算。生命周期只在转换时写日志，只检查未终止指令的对象引用和 O(1) 阵营收入缓存；不每 tick 扫全世界或写快照。

```powershell
python evaluate/micro/CASE2-LTE/evaluate.py evaluate/output/micro/CASE2-LTE/<run-directory>
python -B -m unittest discover -s evaluate/micro/CASE2-LTE/test -p test_lte.py
```

指标由 `metrics/*.py` 插件离线计算，产物 `eval/metrics.json/report.txt/report.html`，插件错误独立显示，不影响其他项。见 [METRICS.md](METRICS.md)。本版核对原生 Replay 指令，生命周期来自 live 引擎，不声称已经通过独立 re-sim。

## 4. Greedy baseline

```powershell
python evaluate/micro/CASE2-LTE/greedy.py --port-file agent-operation/tmp/lte-a/port
```

基线只通过持久 CLI 读取三类公开观察，不读证据。新建候选先选 `pathDistance` 最短的空矿，平局按数值资源 ID；距离是从建造者位置出发的既有 `AiMapGraph` 四邻接静态 LAND BFS，取矿脉四邻接可通行格的最小值×20（矿脉格本身对 LAND 不可通行，忽略动态建筑和软碰撞，不声称精确行驶路线）。

候选 `score = Δincome / (fundingWait + travel + warmup + work)`；`fundingWait=max(0,cost-credits)/(income×60/40)`，收入为0且资金不足时为无穷；建造 `travel=pathDistance/(0.8×60)`、warmup=30/60，work=1000/60；升级 travel/warmup=0，work=升级工作tick/60。按 score 降序、预计时间升序、build 优先于 upgrade、稳定数值 ID 排序。

建造者忙时不提交新建，但可向空闲建筑升级。升级候选会为建造者尚未支付的建造费用预留资金，避免抢走地基款；独立且买得起的候选可连续提交；不能同一建筑排队多次。睡眠选下一候选的预计可支付时刻与所有在途指令剩余完成时刻的最小值，加2个名义tick；重新观察后根据真实剩余进度重算，不固定周期轮询。模型只是一阶贪心估算，不保证最优或完成 tick 完全相同。

`test/run_configs.py` 可按给定任务列表逐个跑 Greedy 并输出结果摘要。

## 5. 源码勘察表

| 源码 / 方法 | 核实结论 |
|---|---|
| `BuilderUnit.getMoveSpeed/addAvailableActions/e` | `builder`，草地0.8，13种建筑含提取器及回收，预热30 |
| `PlaceBuildingAction` 构造器 | `overrideAndReplace` 将原 `extractor` 建造动作替换为 `b_extractorT1` |
| `assets/units/extractor/*.ini` | 表中费用/速率/分支定义；T3收入20，非旧Java的18 |
| `CustomUnitConfigParser.parseAction` / `CustomAction` 构造器 | 动作 ID 来自 convertTo 类型名+定义序号 |
| `MapTile` / `OrderableUnit.canPlaceAtCurrentPosition` | `res_pool` 格属性、放置和碰撞占用规则 |
| `OrderableUnit.a(UnitCommand,UnitType,...)` | 创建、放置校验、全额扣款、地基；缺款保留原生重试 |
| `OrderableUnit.updateRepairOrReclaimCommand` / `BaseUnit.r` | 预热后累积施工进度，完成时更新阵营收入统计 |
| `FactoryQueueManager.a(float)` / `CustomUnit` 动作转换 | 建筑队列自主进度，升级转换保持建筑ID，更新阵营收入 |
| `PlayerTeam.b/getResourceDrainRate` / `CustomUnit.update` | 标称收入与40tick实际发放口径 |
| `CommandController` / `ReplayEngine.a(Command,int)` / `Command.executeCommand` | 录制前、执行前授权，索引在真实ReplayWriter插入处记录 |
| `RwxCliTool` / `RwxEngineLocalGameService.issueOnGameThread` | CLI白名单与通用服务直接入口拦截，含直接姿态修改路径 |
| `LteSession` | 仅授权本Case创建的命令对象，并复核actor/动作/类型/目标/队列等；直接原生命令也被拒绝 |

任意本地 Java 代码直接篡改引擎内存不属于命令权限边界。GUI、人类控制和独立重放复算不是本版验收对象；窗口只用于观看与结算动画。
