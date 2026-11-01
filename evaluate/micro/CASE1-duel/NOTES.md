# CASE1-duel：P0 源码调研

> **状态说明（task041）**：本文是 2026-10-30 早的 P0 调研快照，保留当时的推理与源码出处；其中标“尚未实现/未验证”的条目大多已在 P1–P5 完成。**当前行为以 [START.md](START.md)、[commands.md](commands.md)、[METRICS.md](METRICS.md)、[PROGRESS.md](PROGRESS.md) 为准。** 下文的历史数字（core:test 28 套件/243 项、100 tick 默认、无 micro 目录等）不是现状。

日期：2026-10-30。范围：`prompt.md` 的 R1–R13，只读检查现有实现，另做现有 Headless 包的启动烟测。本文中的“已确认”指**P0 当时**有源码或检查直接支持；结论若已被后续实现取代，条目里会标“P0 时/现已”。

## 当前结论与待确认项

P0 已完成（P1–P5 后来也已完成）。P0 当时触发 `prompt.md` §12 的停工条件：“内置 AI 无法按 §1 的行为配置”。原版攻击组随机混用 `attackMove` 和 `attack`，选目标使用 `Math.random()`；现有配置不能把它限定为“锁定我方且只做 attack_move”。在用户确认前，不修改原版 AI，不引入替代策略。

建议供用户确认的最小替代：通过仓库已有 `AiStrategy` / `AiDefinition` 扩展增加 CASE1 专用敌方策略；关闭所有原版宏观阶段，仅向我方(team0)的存活目标位置发原生 attack-move，稳定处理目标失效及目的地更新，不增加躲避、撤退、生产、建造或投降。所有命令经 `AiContext.command`，继续使用引擎的自动寻敌、武器、移动与寻路。它属于新增敌方策略，不能称为现有原版 AI 的配置。

地图也存在规格差异，见 R6：实际为 team 0、1 各 5 个指挥中心，另有 team 2 的 1 个中心（隐藏 set 层）。用户已定稿：环湖直接使用 team0 为我方、team1 为敌方，其他单位一律删除；原始 TMX 不改。

补充要求已实现：复杂参数支持 `evaluate/micro/config.json`（另可用 `--case-args` 覆盖）。当前文件内容为 `CASE1-duel`、`task=1_2_2`、`seed=1`、`track=pause`。pause 的默认回合长度在 task040 按用户要求改为 **60 tick**（引擎时钟 `t=0.960`，名义 1 游戏秒；此前的 100 tick 记录保留为历史）；命令命名空间统一为 `rwx-micro-1-*`，`rwx-help` 改由 `rwx-micro-1-help` 替代。

## 源码索引

下面使用文件名与方法名定位结论；路径均相对仓库根目录。

| 简称 | 文件 |
|---|---|
| CLI 目录 | `core/src/main/java/com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/` |
| AI 目录 | `core/src/main/java/com/corrodinggames/rts/game/ai/` |
| 引擎逻辑 | `core/src/main/java/com/corrodinggames/rts/game/GameLogic.java` |
| 游戏框架目录 | `core/src/main/java/com/corrodinggames/rts/gameFramework/` |
| 单位目录 | `core/src/main/java/com/corrodinggames/rts/game/units/` |

## R1：CLI、隔离与网关（P0 快照；profile/help/网关均已实现）

- **注册和分发已确认。** CLI 目录的 `RwxCliCommands` 枚举是现有命令名和 domain 的唯一来源；`RwxCliTool.handle` 解析纯文本或 tab + base64 argv，先查 `find`，ping/help 在传输层处理，其余交给 executor。`AppSession.executeCliCommand`（`core/src/main/kotlin/io/github/rwx/app/App.kt`）再按 domain 路由；游戏命令委托 `RwxLocalGameCommands` 与 `RwxEngineLocalGameService`。
- **隔离入口已确认。** P0 时 profile 尚不存在；现已实现 `MicroCommandProfile`。 在 `handle` 查注册表时按本次 case/track 过滤可用命令，必须早于 ping/help/常规 executor 分支；不能只在 `AppSession` 分发时隐藏，因为 help 在传输层就返回。新增 micro domain/注册项后仍需要 profile 限制；普通模式也不应意外暴露 micro 命令。
- **现有帮助来源已确认。** `RwxCliTool.helpDocument` 从 classpath 读 `help.md`；`core/build.gradle.kts` 打包 `agent-operation/help.md`。现已按 case 返回专用 `commands.md`，普通模式文档路径不变。`terminalFunctions` 也要按 profile 生成。
- **客户端兼容性已确认到协议层。** `agent-operation/tools/rwx-cli.py` 使用现有 framed TCP；micro 无需新传输协议，保持命令名 + argv + 文本回执即可。新命令的完整往返已在 P1 实跑。
- **采集现状已确认。** `RwxCliTool.serveClient` 在 `handle` 前调用 `RwxCliCommandCapture.receive`；后者调用 `evaluate/utils/CommandRecorder.java`。TXT 只有收包时游戏毫秒、玩家/联盟/身份/match 与命令文本，没有回执、单调墙钟或唯一请求号。`!!framed` 握手不计请求。
- **最小扩展路径。** 在同一收包点冻结 seq、`System.nanoTime()`、gameMs/tick/phase，回执后补 wallReplyMs/status；别名解析和跳过死亡名单由委托结果补齐。micro 仅切换录制 sink 到 `gateway.jsonl`，避免另起服务器或重复记录同一请求。B 类和 batch 子项以同一个注册表分类，不能用命令字符串前缀猜 A/B/C。此扩展尚未实现。

## R2：启动参数和配置（P0 快照；`--evaluate CASE1-duel`/`--case-args`/`--micro-config` 已实现）

- **现状已确认。** `core/src/main/kotlin/io/github/rwx/app/AppOptions.kt` 的 `parseArgs` 用 `args.forEach`，只识别无值 `--evaluate`，将 `evaluate=true` 并开启 CLI；`applyEvaluationDefaults` 默认设置 `rwx.evaluate.dir=evaluate/output/events-live`，显式属性优先。P0 时还不支持 `--evaluate=CASE1-duel`、`--case-args` 或 micro JSON；现已支持。
- **兼容改造路径。** 改成带索引的参数读取：无值且下一项是开关/不存在时保留旧行为；有明确值时查 `MicroCaseRegistry`，非法 case 直接报错。case 模式不能继续无条件设置旧 `rwx.evaluate.dir`，否则会违背默认不写 liveSnapshots 的约束。`AppOptionsTest.kt` 已覆盖无值旧行为和显式证据目录优先。
- **多实例入口已确认。** `desktop/src/main/kotlin/io/github/rwx/DesktopMain.kt:main` 在初始化前将 data-dir 解析到 `launch.dir`、cli-port-file 解析到 `rwx.cli.portFile`。Headless 与 GUI 共用 AppOptions；micro 参数应在这条路径中传递，不能只加在 HeadlessMain。
- **配置建议，未实施。** 新增明确的配置文件参数，例如 `--micro-config=evaluate/micro/config.json`，依次采用默认值、JSON、显式 `--case-args`，严格检查未知键和重复/无效值。保留现有文件内容直到实现时明确迁移；`id=1_1` 不能当作 `task=1_1_2`。100 tick 的默认值优先于 prompt 中较早的 2 秒默认值；显式 turnSeconds 的换算、实际 ticks/时长需同时写入 meta。

## R3：内置 AI 能力与停工原因（P0 快照；CASE1 AiStrategy 已实现）

- **P0 时 micro 目录尚不存在。** AI 目录下已有 strategy SPI、custom AI 和 CLI；CASE 框架现已实现。
- **原版不能通过现有配置达成要求。** `UnitGroup.updateAttackAI` 在 commandTimer 到期后，约 80% 概率发 `setAttackMoveTarget`，其余发 `setAttackTarget`；还包含集结、重定位、超时销毁攻击组等逻辑。`AIController.getRandomEnemyUnit` 通过 `Math.random()` 随机选择符合敌对关系的单位，没有固定目标队伍/强制命令种类的配置参数。
- **地图选项也不足（P0 结论；CASE1 已改用专用 AiStrategy，不再依赖这些地图选项）。** 游戏框架目录的 `mission/MissionEngine` 解析 team_info 的 basicAI、disabledAI、lockAiDifficulty、allyGroup、ai=survival；basicAI 只设置 `isAggressive`，disabledAI 则跳过 AI 更新。`ai=survival` 会设置 `isTeamObserver`，在 `AIController.updateAILogic` 后段存在定时全军attack-move分支，但同一方法此前仍有闲置重定位/wayPointTimer超时清令等通用逻辑，目标也沿用Math.random；它不能保证“开局即锁定、只发attack_move、稳定目标选择”。不能忽略这个分支后声称引擎完全没有单纯攻移能力，也不能把它冒充全部规格已可配置。
- **已有扩展可以实现替代，但需要确认。** `strategy/AiDefinition.custom` 默认不运行任何原版阶段；`AIController.updateTeam` 执行 custom runtime 后在 `!runsAnyOriginal()` 时返回；`strategy/AiRuntime` 提供模拟线程回调和存档状态。可用最小专用策略限制敌方发令，无需重写武器或寻路。现有 `custom/ai_autopilot` 是继承原版 Impossible 的骨架，直接使用它仍会运行全部原版阶段，不满足要求。
- **录像覆盖已确认。** `strategy/AiContext.command` 通过 `CommandController.newCommandForTeam` 发令；`CommandController.executeLocalCommands/executeNetworkCommands` 在执行命令前统一写 replay，不按玩家/AI 来源过滤。custom runtime 在 replay 播放时不再运行，避免同一 AI 命令生成两次。
- **结论（P0）。** 当时不能以“内置 AI 已支持该配置”继续 P1；用户随后批准了最小扩展，现已实现。

## R4：单位数值与单挑机制

配置来源：`assets/units/mechs_large/mech_minigun.ini`、其 `mech_med_common.ini`，以及 `assets/units/mammoth_tank/mammoth_tank.ini`。下表为资源配置和源码计算，尚未在新 CASE 中实测；时间换算按名义 60 模拟帧/秒。

| 字段 | 机枪机甲 | 自定义猛犸 |
|---|---:|---:|
| 类型 | mechMinigun | c_mammothTank（替换原生 mammothTank） |
| HP / 护盾 | 600 / 800 | 2600 / 无 |
| 护盾回复 | 0.08/tick（无受击延迟；从 0 回满需 10000 tick） | 无 |
| 基础移速 | 0.6 世界单位/tick（60 tick 约 1.8 格） | 相同 |
| 加速 / 减速 | 0.07 / 0.17 | 0.04 / 0.06 |
| 移动类型 | OVER_CLIFF | LAND |
| 质量 | 6000 | 14000 |
| 视野 | 显式 20 格 | INI 未显式设定；运行时默认 15 格（已确认） |
| 武器射程 | 210 世界单位 / 10.5 格 | 190 / 9.5 格 |
| 基础伤害 | 55 | 130；对建筑倍率 0.5 |
| 基础 shootDelay | 34 帧（满预热 15 帧） | 50 帧（另加 30 帧预热 = 80 帧/发） |
| 预热参数 | warmup=200，warmupNoReset=true，transfer=19 | warmup=30，无 warmupNoReset |
| 身体转向上限 / 加速 | 5.0 / 0.5 | 1.4 / 0.15 |
| 主炮塔转向上限 / 加速 | 2.9 / 0.3 | 2.5 / 0.18（另有减速 0.35） |

**射速差异。** 单位目录的 `custom/CustomUnit.b/e/f/s/t` 将上述参数送入 `OrderableUnit.advanceMovementLevelTowardTarget`。机甲持续预热时装填项为 `34 - 19 * warmup/200`，满预热下为 15 帧，名义 0.25 秒；裸参数 DPS 为约 97→220，不是 prompt 的 92→170。实际发射间隔还受 tick 粒度、炮塔对准及目标条件影响，不能把裸参数值冒充实测。

猛犸每次发射后预热归零，代码在装填归零后累加非持续预热，因此 50 帧不能直接当作完整发射周期：还需约 30 帧预热，理想连续周期约 80 帧，名义 1.33 秒；后续实测确认 80 tick/发（1280 game-ms）。后续“多开几轮炮”的分母应基于确认后的实际武器周期，至少分开记录装填和预热，不能写死 0.8 秒。

**穿盾精确语义。** `custom/CustomUnit` 将 shieldDamageMultiplier 映射到 `Projectile.targetDamageMultiplier`、shieldDefectionMultiplier 映射到 `splashDamageMultiplier`；`BaseUnit.applyDamage` 对有足够护盾的目标扣 `D * shieldDamageMultiplier` 护盾，剩余 HP 伤害为 `D * (1 - shieldDeflectionMultiplier)`，再应用 HP 伤害倍率。因而猛犸对足盾机甲一发扣 **130 盾、117 HP**，不是只扣 13 盾。忽略其他伤害/回复时，600 HP 理论上需要 **6 发**，不是 5 发；护盾不足时另走扣尽护盾的分支。正式指标须直接记录前后 hp/shield，不能从弹种反推。

**warmup 不是停火即清零。** `OrderableUnit.updateAttackTargeting` 在不满足持续活跃条件时用 `moveTowardsZero(speed, callDownRate * delta)` 衰减；机甲 callDownRate=1、上限200，完整预热名义约需 3.33 秒才退完。`advanceMovementLevelTowardTarget` 对 warmupNoReset 的炮塔在发射后保留预热。该字段储存在 `movementLevels[i].speed`，名称并非单位移速。换目标、move/stop 命令没有在这里直接清零；是否衰减取决于是否仍有可用目标/武器活跃条件。holdFire 会阻止自动目标获取（`OrderableUnit.a(BaseUnit,boolean)`）。各操作的动态时序仍需实战逐项验证。

**能边走边射，但 attack-move 会停步。** `OrderableUnit` 分别更新 waypoint/移动意图和 `updateAttackTargeting`，两份单位配置没有禁止移动射击。攻移会调用 `updateAttackTargetState(..., true)`：目标进入合适射程且可交战时清除 `isPathingActive`，超出时又追向当前目标位置。停止前进是攻移行为，不能推断成猛犸武器本身强制停车。普通 move 没有同样的攻移停步语义。

**地形与 1v1 的机制分析（P0 推断；后续 task039–041 的多局实跑只支持“20 单位射程差 + 风筝可成立”，仍未证明单机甲必胜）。** 已确认的优势包括 20 世界单位射程差、机甲更强的加减速/转向、猛犸炮塔对准和每次发射的预热，以及 OVER_CLIFF/LAND 路径差异（R6）。这些能造成可利用的移动/转向/充能空窗；猛犸攻移在射程内停车后重新起步也有加速损失。双方基础最高移速相同，直线同速后退不能自动扩大已有距离；地形可以使猛犸绕行，机甲可走更短路径。推断：组合以上机制可能解释熟练玩家的 1v1 胜利，但 **没有实测证明 1 台机甲必胜，亦未验证具体地图上能否无伤击败猛犸**。不得将这些推断写成 Agent 文档里的打法保证。

## R5：可观测字段

- **现有输出已确认。** CLI 目录的 `RwxLocalGameTypes.UnitSnapshot` 有 id/type/team/relation/x/y、health/maxHealth、移动类型、当前 waypoint/order、目标 id、stance 和队列；`RwxLocalGameCommands` 默认 unit-list 只列 HP<80%，`--full` 或其 ignoreHp 路径取消过滤。
- **现有缺口已确认。** P0 时此 UnitSnapshot 没有 shield/maxShield、各炮塔冷却/预热、朝向或最近受击时间（后来 `MicroSnapshots` 已补 shield/maxShield/weapons/lastFireMs/mode/pending）；引擎存在 `BaseUnit.shield/unitEnergyMax`、`rotationSpeed`、`bs`、`OrderableUnit.attackTarget`、`lastWeaponFireMillis`、`movementLevels[].rotation/speed/targetUnit`，可只读补充，不能把字段名 speed 当单位速度。
- **敌方隐私语义已确认。** `RwxEngineLocalGameService.snapshot` 仅在 `unit.team==controlled` 时暴露订单/队列/stance，敌军即使可见也没有这些字段。micro 已用 case 专用只读补充展示双方武器/当前目标，未放宽普通 CLI 的敌方订单权限。无法取到的字段用 null/N/A，不伪造 idle。
- **可见性已确认到源码。** `isObservable` 采用本方或 `unit.d(controlled)`；关闭雾后普通敌军应可见；CASE 的 A 类全知输出已实现。micro-units 要以委托得到的单位集合为基础再添加只读字段。
- **暂停队列缺口。** 录像期间 pendingCommands 尚未应用；micro 已只读叠加相同单位的待发订单并标 `pending`。

## R6：地图、出生位和寻路

- **实际资源路径**是 `evaluate/resource/maps/环湖_单挑.tmx`，不是 prompt 中的 `resources`。原图 SHA-256：`506510c69df7b7c5f800337373af7d456361d8f603e2642d070df7416cc7a13c`。本次用标准库 XML + base64 + gzip 解码全部 tile layer；图为 **111×111 格、每格20世界单位**，有 set/Ground/Items/Units 四层，map_info 的 type=skirmish。
- **出生位以运行时为准。** Units 层实际解码为 10 个 commandCenter（team0/1 各5），可直接传给 `PlayerTeam.k(int)`，不是隐含减一后的队号。另有一个 team2 中心写在隐藏的 `set` 层，但 `TileMap.loadMap` 明确跳过 `set`/`set-disabled` 层（`TileMap.java:1093-1098`），运行时不会生成；因此“其他单位”实际为 0，布景仍保留删除兜底。

| TMX team | 运行时数量 | tile 坐标（x,y） |
|---|---:|---|
| 0 | 5 | (30,23), (18,36), (13,51), (20,71), (32,87) |
| 1 | 5 | (74,23), (87,35), (94,53), (84,78), (67,88) |
| 2 | 0（set 层被跳过） | (41,22) |

- **布景方式（2026-10-30 用户定稿）。** 环湖地图直接使用 team0（我方）与 team1（敌方），不再做 0→1、1→2 映射；除双方按 seed 选出的 a/b 个中心外，加载后的其他单位一律删除（set 层的 team2 中心本就不会加载，仍保留兜底）。源图字节不变；meta 保存 sourceTeam=team、目标位置、id 与别名，并记录 `discardedUnits` 与被删的未选中中心数，供重复布景核对。
- **加载接口已确认。** `TileMap.loadMap` → `openAssetStream` / `FileHelper.openFileByPath` 支持路径与流；`GameSession.loadMap` 设置 currentMapPath 后调用 `engine.loadGame(true, GameMode.normal)`。Tileset source 为 terrain/ridges 等现有资源名，新图需要复用合法资源路径和 tile 属性。现有 `getOrCreateTileFromTilesetIndex` 是 tile 复用设施；CASE1 的 4–8 区域/预设水形/BFS 悬崖环和碰撞校验已由 `Case1Map` 新增。
- **寻路类型已确认。** `RwxEngineLocalGameService.reach` 用实际 `unit.getMovementType()` 选择 `AiMapGraph.of(movement)`，并检查本方控制权和地图坐标；机甲按 OVER_CLIFF，猛犸按 LAND。类型1生成验证应直接使用 LAND 图/引擎地面路径，不用“看起来连通”的字符网格代替。
- **-7 不能一概解释为机甲可过。** `RwxTerrainCodes.classify` 将 cliff、负 movementBlockLevel、禁止放置等归为 -7。游戏框架目录的 `pathfinding/PathCostMap` 允许 OVER_CLIFF 跨 isCliff/hasLargeObject，但仍拒绝水、熔岩和显式负通行成本。因此图例须区分可跨悬崖与一般阻塞，不能声称所有 -7 都可跨。猛犸面对悬崖时是否绕路/失败取决于实际 LAND 连通性；本图的具体追击未验证。

## R7：结束判定（P0 快照；`afterTick` 的逐 tick 判定已实现）

- **原版不符合逐 tick 双灭裁决。** `PlayerTeam.e(float)` 有 defeatCheckDelayTicks；`mission/MissionEngine.update` 的常规胜负检查约每1000游戏毫秒执行，并依 waveSpawnMode 过滤目标。无建筑并不等于立刻败：单位仍可满足 allUnitsAndBuildings，但不能复用 mainBuildings 的判定。
- **最小方案。** CASE 在一次完整模拟 tick 的末尾统计 meta 中双方战斗单位，先检查同 tick 双空，再判胜负/超时；统一冻结、关闭录像并写 result。不要依次处理两个 death listener 就立即判一方获胜，也不应依赖 UI 胜负标志提前结束。`GameLogic.update` 末尾的证据 hook 已接入 `afterTick`；已实现。

## R8：时间、回合和暂停命令（P0 快照；pause `begin/next` 已实现）

- **名义时长与实际整数时钟需分开。** `GameLogic.update` 用 `f * 16.666666f` 增加 gameTimeMillis，但字段为 int；录制/回放时通过 accumulator 以 `networkEngine.getCurrentStepRate()` 固定步进（默认1帧）。因此默认1帧一步时每次整数增加16ms，100 tick 的该整数游戏时钟会增加1600ms，不能盲写成1667ms。meta 应记录实际 tick/时钟口径，跨证据匹配以 tick 和采到的 gameMs 为准。未录制普通单机则可直接 update(float1)，不能把它当固定 tick 基线。
- **现有 step 不满足 pause 赛道。** `RwxEngineLocalGameService.step` 是提速运行、轮询 gameSnapshot、Thread.sleep、最后暂停的实现；有超时和过冲可能，不可直接包装成 micro-next。
- **引擎内暂停可做最小接入。** 在 `GameLogic.update` 的 tick 结束点判断目标 tick；还需让外层 accumulator 循环在达到边界后停止，防止同一渲染帧继续跑多个 tick。next 在 CLI 线程等待回合完成，游戏线程不等待请求线程；全速回合应以固定步进循环执行，而非 gameSpeed 放大单次 delta。该机制已实现并实跑（`MicroRuntime.advance`）。
- **录像期间的暂停订单语义已确认。** `ReplayEngine.i()` 表示录制或播放任一种活动态；`canApplyImmediately` 在该态返回 false，订单留在 pendingCommands。`CommandController.executeLocalCommands` 在下一 update 开头、currentTick 自增前记录并执行；因此 replay 的命令 tick 通常对应本次 update 的起始 tick，gateway turn 边界是 update 完成后的 tick，验收要明确这一偏移，不应误判早/晚一 tick。
- **批量接入方向。** 同一游戏线程任务内委托各子命令，确保不会在两次委托之间推进 tick；仅通过客户端连续发送无法保证。末尾 next 必须在释放游戏线程后等待，避免死锁。只读输出叠加 pending，保持实际应用在下一回合首个 update。

## R9：现有评测证据和口径（P0 快照；micro collector 已实现）

- **可复用 hook，不能直接复用旧状态格式。** `evaluate/utils/EngineEvidence.java` 已接入 beforeTick/afterTick、对象观察、原生命令前后、损伤与移除；记录 telemetry.jsonl、economy.log。每 tick 有 frame，位置更新上限约1秒且仅变化时写；这不是250ms全量双方快照。
- **P0 时关键字段缺失（后来 micro collector 已补 hp/shield/伤前伤后与 250ms 快照）。** `EngineEvidence.snapshot` 含 id/type/side/x/y/alive/task/queue/idle 等，没有 hp、shield、武器冷却/预热；damage 行也只是事后双方 snapshot，没有本次 HP/盾差值。micro 需要在共用 hook 上挂 case collector，获取伤前/伤后差值，并按250ms输出双方完整状态。不能拿旧 telemetry 冒充符合 micro 契约的 snapshots/events。
- **默认路径。** 主路径是在 replay 播放期间采集这些新字段；liveSnapshots=false 时运行中不写该状态流，只有 gateway/meta/result/录像。重放不通过才按 prompt 启用相同格式的 live 回退。
- **A/B/C 与周期已确认。** `evaluate/evaluate_stage_1.js` 的 `kindOf` 将查询计A、order/管理操作计B，其余计C；`splitSegments/analyze` 先滤C，再将连续A段和后续连续B段合并，周期为“首条A→最后B”；B→A等待是相邻有效段末B→下A。micro T2 应继承此定义，墙钟用 recv/reply 算思考时间并避免重叠计数；batch 父行与子行不能双计B。

## R10：确定性（P0 快照；V4/V10 已做同种子对比）

- **地图选择需独立 RNG。** `Utility` 同时包含共享 Random 与依对象/tick/seed计算的确定性随机接口；不能将布局/数量建立在全局随机调用次数上。可为 MicroCase 用 task+seed 的局部 PRNG，稳定排序后选择/生成并保存重试次数与输入种子。
- **单位 id 来源已确认。** `GameObject` 构造取 `NetworkEngine.y()`，其 nextUnitId 单调递增；新网络状态会重置该计数。原图加载、临时对象和额外中心都会消耗 id，需稳定移除/重建顺序，并核对两次独立启动，不能仅靠给生成器固定 seed 就宣称 V4通过。
- **原版 AI 另有不确定性。** `AIController.getRandomEnemyUnit` 使用 Math.random()，房间 randomSeed 无法固定这处目标选择。候选专用策略须有稳定的目标排序/状态保存；replay 播放以已记录 AI 命令为准，不应运行第二次敌方策略。
- **尚无跨启动对比。** 同 task/seed 的地图、数量、坐标、id、别名与实际战斗复现均留待 V4/V10。

## R11：构建和实际运行环境

- **本环境可以运行现有 Headless 包。** 本次用 `A:/javas/java25/bin/java.exe`（25.0.4.1）、`desktop/build/headless/lib/*` 启动独立数据目录与独立端口文件；观察到 `RWX_HEADLESS_READY`，从新端口发 `rwx-ping` 收到 `pong`，随后 `rwx-exit` 正常退出，进程 exit=0。未使用旧端口快照，也未强杀。烟测只证实现有包可启动，不能当作新 micro 实现验收。
- **当前启动方式**：见 [START.md](START.md) §0 或 [evaluate/micro/README.md](../README.md) §1。P0 烟测曾直接用已构建的 `desktop/build/headless/lib/*` 启动；该命令暴露仓库内部产物布局，已废弃，不再写进 Agent 文档。
- **构建与回归已确认。** `core/build.gradle.kts` 的 Java/Kotlin toolchain 均为25；`desktop/build.gradle.kts` 提供 headlessDist/headlessArchive。P0 当时的 XML 汇总为 28 套件/243 项；截至 task041 为 29 套件/248 项（见 PROGRESS.md）。P0 未构建 Headless 发行包，micro 功能当时尚未实现。

## R12：Replay 和 re-sim（P0 快照；micro resim 已实现并跑过闸门）

- **文件格式已确认。** `ReplayEngine.d(String)` 写 rustedWarfareReplay 标头、协议96、版本和 gamesave 初始存档，再由 ReplayWriter 写命令/校验/结束块。`evaluate/utils/replay.py` 和 `evaluate/utils/README.md` 已说明 rc/wait/cs/es/resync/end/endReplayMetaData 以及底层 Command 字段。
- **初态不只是地图名。** `GameSaver.writeSaveToStream` 写 currentMapPath、可选 remoteMapStream、完整对象/队伍状态；初始 gamesave 能保存布景后的单位，录制必须放在布景完成后，否则首次replay会恢复错误的中心/单位。外部 TMX 仍需保留，普通本地保存不保证内嵌原始地图字节。
- **不要重复布景。** 新 run 使用可复用 setup(meta) 建立场景；replay 恢复的是已布景初态，载入后只“绑定/核对”meta 与 id 并接采集器（`MicroRuntime.replayLoaded`），不再生成单位或重置 AI。P1b 已实测通过。
- **自动录像入口已确认。** `requestLocalRecording` 是下次加载前预先arm的机制，正常加载完成就开录；但micro还要替换中心，故应在新布景后显式 `ReplayEngine.d` 开录并检查 isRecording，再写可提交的 meta。结束调用其正常停止路径，等待writer收尾后把录像放入run；路径受 ReplayEngine.a 的replays目录约束，需要独立命名后复制/链接，不能假设任意绝对路径都可写。
- **现有离线入口。** `evaluate/utils/evaluate_replay.py` 只接受本仓库replays下的录像，要求原commands，启动 Headless `--auto-replay` 并加 replaySpeed/exitOnReplayEnd，然后运行第二轮脚本。micro 已复用其驱动/校验设施并增加无 gateway 输入路径（`common/resim.py`），不为人类 replay 伪造 commands。
- **P0 时校验闸门尚未运行；后来已跑（见 PROGRESS 的重放闸门表），本地证据已清理。** 旧脚本汇总 checksums don't match 的日志条数，但micro还必须比 outcome、全部单位终态hp/shield及kill tick序列；零checksum警告不能替代这些对比。录制期间暂停不记录墙钟思考时长，离线replay仍按底层tick执行，正是T1可比而T2不可由replay恢复的原因。

## R13：GUI 人类模式

- **已有直接加载基础。** `GameSession.loadMap`、地图请求/launch controller，以及 `desktop/src/main/kotlin/io/github/rwx/slick/SlickGame.kt` 的载图分支可直接进对局，不要求人工先走战役室；`App.kt` 的autoReplay也是启动后调用现有launch controller。micro已走同类请求，加载/布景/开录在模拟开始前协调。
- **人类操作与 Agent profile 分离。** human模式保留普通GUI/命令入口，控制team0；载入后暂停在tick0，解除暂停时 `MicroRuntime.allowTick()` 触发 `begin/human-resume` 生命周期。
- **结果界面基础已存在。** `MissionEngine` 调用 gameUI 的结束序列，但其胜负判定不是CASE裁决；micro结束后写 `agent=human` 的 result，未替换 UI 胜负界面。
- **仍未验证（task041 状态）。** GUI 未实跑：鼠标控制 team0、结果显示、开局后禁用暂停/变速都没验证；`mode=human` 目前只把“解除暂停”当开局，**没有强制锁 1×/禁暂停**。人类基线需要人工监督。

## 后续验证闸门（P0 时计划；实际结果见 PROGRESS.md）

P0 当时列的 P1→P5 顺序已被实际执行覆盖：P1 类型2 纵切、P1b re-sim、P2 类型1+600 种子、P3 命令全量（回合长度最终定为 60 tick，不是 100）、P4 指标 fixture/replay-only/human 列、P5 Agent 文档与 V1–V12 矩阵均已完成；未完成的只剩 GUI 人类实玩与真实人类基线（`baselines/index.json` 仍为空）。

不实现暂缓指标、不编写脚本评分基线、不更改原版单位数值来迎合prompt估算值。

## task042：参与者文档的规则核对（2026-10-30）

仅 START/commands 面向参与者，删除攻略、击杀时间、AI 决策周期/阈值及评价器实现；任务与公开武器/操作规则保留。本节供维护者追溯，不分发给参评 Agent。

| 规则 | 核对结论 | 代码证据 |
|---|---|---|
| 评分 | 无加权总分，辅助指标不改胜负 | 本文件对应实现记录、METRICS.md 的排序与比较 |
| `attack` | 射程外主动接近/追击；目标失效且有后续订单则推进，无后续可重新索敌 | `OrderableUnit.updateAttackCommand/updateAttackTargetState` |
| 队列 | 最多 29 条有效订单含当前，溢出项未进入计数；移动队尾附近请求会合并；pending 不是完整 waypoint 队列 | `OrderableUnit.appendWaypoint/advanceWaypoint`、`Command` 的 `isHighPriority` 分支、`MicroSnapshots.unit` |
| 不可达 | 寻路可返回替代终点/部分路径；普通 move 可在最终路径结束后推进；attackMove 仍按原目标/交战状态判断完成，可能反复寻路 | `pathfinding/PathSolver` 的 fakeEnd/closest node 分支、`OrderableUnit.applyMovementToward/updateMovementStateFromCommand/updateMoveAttackMovePatrolCommand` |
| 预热 | 有效目标、射程、炮塔方向、姿态参与；机甲可在装填中积累，猛犸装填完成才充能；中断渐退，非一律清零 | `OrderableUnit.updateAttackTargeting/advanceMovementLevelTowardTarget/u`、`CustomUnit.e/f/s/t` |
| 弹道 | 机甲 speed=11、life=160；追踪原目标，无死亡自动换目标/飞行重选；无“飞出射程销毁”规则 | `assets/units/mechs_large/mech_minigun.ini`、`ProjectileTemplate` 默认值、`Projectile.update` |
| 碰撞 | 双方地面碰撞组；半径与质量参与软挤压；0 的软碰撞参数不表示无实体碰撞 | `BaseUnit` 的 collisionGroup 赋值处、`CustomUnit.getPushMass/getSoftCollisionDivisor`、`OrderableUnit.resolveSoftCollisionWithUnit` |
| 护盾 | 猛犸盾扣 min(S,130)、HP 伤害 130−0.1×min(S,130)；足盾实际扣 130 盾和 117 HP，非 130 守恒分配 | `BaseUnit.applyDamage`、`CustomUnit` 的 Projectile 倍率映射 |

task041 参与者文档中的“质量不参与碰撞”“同一发 130 的分配（10% 穿盾）”已纠正；不更改单位数值或战斗引擎。

隔离实跑（task=1_1_2/seed=7/pause，延迟敌方策略仅用于规则验证）共 51 请求：射程外 attack 的机甲实际接近目标；holdFire 下进入射程但 lastFireMs 仍为 -1；改 onlyInRange 后 lastFireMs 更新；再改 returnFire 后不再更新。`rwx-micro-1-help` 全文与最终 commands.md 一致，help 2 含新增队列章节，help --list 含第 6 章。完整 core 回归 29 套件 248 项全过、Headless 打包成功；临时脚本、日志、布景与录像已清理，不留作策略基线。
