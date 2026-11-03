# CASE2-LTE 命令手册

## 1. 总览

| 类别 | 命令 | 参数 |
|---|---|---|
| A | `rwx-micro-2-state` | `[--json]` |
| A | `rwx-micro-2-builder` | `[--json]` |
| A | `rwx-micro-2-resources` | `[--json]` |
| B | `rwx-micro-2-build` | `--resource <R1..Rn>` |
| B | `rwx-micro-2-upgrade` | `--building <buildingId>` |
| C | `rwx-micro-2-begin` | 无 |
| C | `rwx-micro-2-help` | `[--list|章节编号]` |
| C | `rwx-ping` | 探活 |

只在CASE2-LTE注册。任务 ID 为 `2_<矿脉数 4-8>_<档次 1-5>`，初始资金固定 2100。没有forecast/ETA、wait、pause、next、step、speed、restart、surrender、batch或任意action/坐标/类型参数。

## 2. 通用约定

A只读、不进入Replay；B经授权后进入原生命令队列与Replay；C begin只开始一次realtime。B只在phase=running受理。观察不带--json为 `ok: observation` 加JSON正文；带--json为纯JSON。文本错误以 `error:` 开头，reason使用英文。phase为briefing/running/ended。

所有观察带status=ok、tick、gameMs、wallMs。gameMs每tick+16；wallMs共享run初始化的单调原点。`ok: submitted requestId=N` 只代表受理，真实开始/完成在后续tick；升级也不会在回执时完成。

## 3. 观察

`state` 字段：task、tier、resourceCount、targetComposition、phase、credits、incomePerSecond、targetIncomePerSecond、targetReached、remainingGameMs、remainingWallMs、economy、builderMoveSpeed、builderWarmupTicks、generationPeriodTicks。remainingGameMs从begin后消耗（默认20分钟）；remainingWallMs首请求后消耗。

`builder` 字段：id/type/x/y、order、phase、resourceId、requestId、buildProgress、moving。phase是当前建造指令阶段（accepted/pathing/moving/arrived/warming_up/constructing/waiting_for_credits）；没有活动建造为idle。buildProgress在还没有地基时为null。

`resources` 字段：resources[]、economy。每行包含resourceId/tileX/tileY/x/y、occupied、pathDistance；占用后含buildingId、type、level、buildProgress、busy、upgradeProgress、availableActions。距离是从建造者当前位置到该矿脉四邻接可通行格的最小四邻接静态LAND最短路径×20；矿脉格本身对LAND不可通行，所以不取矿脉中心。忽略动态单位/建筑；升级排队未开始时进度可为null或-1。

availableActions只含空闲完工T1/T2的下一标准升级ID；不表示资金必然足够。T3无动作。economy每行包含level/type/incomePerSecond/buildCost/buildWorkTicks/upgradeActionId/upgradeCost/upgradeWorkTicks/upgradeRequires，N/A字段为null。

| 等级 | 内部 ID | 引擎 `incomePerSecond` | 直接建造费用 / 工作 tick | 下一标准动作 ID | 升级费用 / 工作 tick |
|---|---|---:|---|---|---|
| T1 | `extractorT1` | 8 | 700 / 1000 | `extractorT2_0` | 1400 / 1÷0.0006 ≈1666.667 |
| T2 | `extractorT2` | 12 | N/A | `extractorT3_0` | 4000 / 1÷0.0003 ≈3333.333 |
| T3 | `extractorT3` | 20 | N/A | 无 | N/A |

建造动作是 `b_extractorT1`。T3 的 `extractorT3_overclocked_0`（超频）、`extractorT3_reinforced_1`（护盾）以及这些分支的降级动作全部禁止。表来自当前加载的自定义单位，不使用旧 Java `ResourceExtractor` 的 8/12/18、1200/2500 或 102/103。

工作 tick 是进度速率的倒数，不含命令排队、寻路、转向、进入施工距离及建造者 30 tick 预热；浮点累计、离散 tick 会产生取整差，实际完成时刻以引擎证据为准。T2/T3 的 INI `price=2100/6100` 是累计价值，不能当成可直接建造的价格。

`incomePerSecond` 与现有游戏 CLI 一致：`team.b(Resource.D)-team.getResourceDrainRate(Resource.D)`。默认资源生成周期是 40 tick，因此标称 8/12/20 是**每 40 tick** 的额度；引擎实测 16 ms/tick（62.5 tick/游戏秒，1x 下同一数值也是每墙钟秒），折算为 12.5/18.75/31.25 credits/游戏秒。不要把字段名理解为实际每 1000 game-ms 入账量。

## 4. 经济操作

`build --resource R1`：唯一builder在指定空矿建T1；不接受其他参数。不足资金由原生订单等待；新建替换已有建造意图，旧请求记superseded，旧地基保留；不隐式排队或继续旧地基。

`upgrade --building 1001`：用实际buildingId让完工、空闲的T1→T2或T2→T3。由建筑自主执行，不需要建造者，可与其他建筑升级/建造并行。买不起则invalid_request: insufficient_credits；忙碌则building_busy。不允许T3分支、跳级、降级或取消。

## 5. 错误与违规

普通错误包括：invalid_request: expected --resource/--building、unknown_resource、resource_occupied、invalid_building_id、not_running、already_begun、building_busy、insufficient_credits。未受理、不改变状态、不进入Replay。

明确越界返回 `error: violation: <reason>`，记录violations.jsonl。非本局actor为building_not_in_case；T3升级为no_standard_upgrade；通用命令及传入动作/坐标/类型为command_not_allowed；通用服务调用为generic_orders_not_allowed；底层未授权对象为unauthorized_native_command。

回收、修理、移动、攻击、停止、巡逻、其他建筑、其他单位及所有未开放命令均禁止。违规有独立violationId、requestId（直接底层调用可能null）、actor、target、原命令、tick/gameMs/wallMs、violationType、reason、rejected=true；不改变任务胜负。

## 6. 生命周期

briefing时先观察，再begin。无自动开始、无暂停。incomePerSecond首次达到目标立即success；任一预算先到为timeout；异常为error。headless保存证据并退出（结算后等待10秒）。结果离线指标见 [METRICS.md](METRICS.md)。
