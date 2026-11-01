# 任务:为 RWX 新增「微操试验场」评测框架,并实现第一个用例 CASE1-duel(单挑)(本版替换此前所有版本)

> 历史任务书。当前实际口径以 [START.md](START.md)、[commands.md](commands.md)、[METRICS.md](METRICS.md)、[PROGRESS.md](PROGRESS.md) 为准：队伍是 team0/team1（不是 team1/team2）；pause 默认 60 tick（`t=0.960`，1 tick=16 game-ms）；下文旧的 DPS/装填/“5 发击杀”等数值已被 task041 校准。

## 0. 背景与架构要点
仓库是 RWX(基于 Rusted Warfare 的 Java RTS)。已有「完整对局」CLI(`rwx-game-*`,见 `agent-operation/help_zh.md`)和两轮实时性评估(`evaluate/README.md`、`evaluate/evaluate_stage_1.js`、`evaluate/evaluate_stage_2.js`、`evaluate/utils/replay.py`、`evaluate/utils/evaluate_replay.py`)。
现在新增一类独立的「微操试验场」(micro cases):小规模固定场景,考察 Agent 的实时操控。本次交付:①框架;②CASE1-duel;③面向 Agent 的 `START.md`/`commands.md`;④外部评测与人类基线流程。
硬约束:不得破坏现有功能——`--evaluate` 不带值时的旧行为、全部 `rwx-game-*` 命令、现有评估脚本与测试必须照常工作。新命令须委托/继承现有 `rwx-game-*` 的处理与校验逻辑(控制权、目标关系、坐标、可达性等),禁止复制粘贴一份。沿用仓库现有风格与依赖,不引入新的重量级依赖。

架构要点(先内化再动手):
A1 Replay 是证据主源。case 模式在布景完成后、tick 0 之前自动开始录像(Agent 无权也无需调用 --record)。录像里的「team + 底层命令」足以复现整局,因此 B 类(操作)命令的精确 tick、作用单位、目标直接从底层命令逆向得到。
A2 高层网关只是「薄参考日志」:只补录像里没有的信息——A 类查询(不是游戏命令)、墙钟时间、被拒绝/跳过已死单位的请求、别名、batch 分组、生命周期事件(begin/turn/end)。优先复用现有 `--evaluate` 的端口命令录制并扩展,不另造一套。
A3 状态证据(位置/血量/护盾/伤害/击杀/任务)由「重放补采」(re-sim,沿用并扩展 `evaluate_replay.py`)离线生成;引擎运行期默认不实时写盘(`liveSnapshots=true` 仅作回退)。新增指标因此不需要重跑 Agent。
A4 人类基线 = 人类在同一布景下实玩一局的回放,走与 Agent 完全相同的评测流程;只有能从 replay 导出的指标进入「人类可比」层。
A5 pause 赛道 = 固定回合制:引擎每 x 游戏秒在精确 tick 边界自动暂停;Agent 不能自行 pause/step/变速,只能用 `rwx-micro-next` 推进一个回合。

## 1. CASE1 规格(以 `evaluate/micro/CASE1-duel/README.md` 为准,勿改其语义;路径不同请自行定位)
- 我方(team 1,Agent 操控,controlledTeam=1)机枪机甲 mechMinigun ×a  vs  敌方(team 2)猛犸坦克 c_mammothTank ×b。
  level1: a=b+1, 1≤b≤4;level2: a=b, 1≤a≤4;level3: a+1=b, 1≤a≤4;level4: a+2=b, 1≤a≤3。a、b 由种子确定性产生。
- 任务 id:`1_<地图类型 1|2>_<level 1|2|3|4>`。
  - 地图类型1:随机生成。长、宽各在 80–120 格随机;草地为底,悬崖 5–10 个、水域 5–10 个;先按 Best-candidate 采样一次性撒好全部中心(第一个中心固定在我方出生点朝敌方 1/3 或 1/4 处且必为水域,其余最小间距 0.65√(WH/N)、离出生点 8 格),再用随机边界生长长出 10–20 格,区域边界间距 ≥5 格(均按 README)。双方出生点沿长边按 README 的「-A--B-」布局,短边取中。
  - 地图类型2:`evaluate/resources/maps/环湖_单挑.tmx`,图上已有 1、2 号队各 5 个指挥中心:team1 保留 a 个并替换为机枪机甲,team2 保留 b 个并替换为猛犸(保留哪几个由种子随机选,写入 meta.json)。
- 敌方由内置 AI 接管:开局后锁定攻击目标为我方,模式 attack_move(以源码实际行为为准,见 §2)。
- 单位数值(以代码实际值为准,差异记入 NOTES.md):
  - 机枪机甲:HP600,护盾800(缓慢回复),移速1.8格/s,可翻越山地/悬崖(over-cliff),视野20格,质量6000。武器=转管炮:单发55,射程10.5格(210),装填34 tick(满预热15 tick),DPS≈220/60 tick,仅打地面。
  - 猛犸坦克:HP2600,无护盾,移速1.8格/s,地面单位,视野15格,质量14000。武器=电磁线圈炮:单发130,射程9.5格(190),预热30 tick+装填50 tick=80 tick/发,DPS≈97.5/60 tick,对护盾 shieldDefectionMultiplier=0.1(一发扣130护盾+117 HP,约6发击杀一台机甲),对建筑0.5倍,仅打地面。
  - 1格=20世界单位。
- 战斗机制(用户确认,P0 须用代码核实并如实记录差异):双方都能边走边射;机甲 `warmupNoReset=true`,停火/移动/换目标不清零,只在没有交战目标时按 1/tick 衰减。常规对局中熟练的人类玩家可用 1 台机枪机甲击败 1 台猛犸。注意:双方移速相同(1.8)、射程 210 vs 190(中心距),存在 20 世界单位的“我打得到你、你打不到我”距离带;「吊打」究竟靠什么(猛犸开火是否停步、炮塔转向、山地/悬崖绕路等)是 P0 必须回答的问题——它决定类型1 地图扰动的意义与各 level 难度。

## 2. 阶段 0:调研(只读)→ 写入 `evaluate/micro/CASE1-duel/NOTES.md`,每条结论带文件/类出处;未能确认的标「未验证」
R1 CLI 命令服务:命令如何注册、解析、分发、生成回执;`rwx-help` 如何提供;如何做到「按 profile 只注册一部分命令」且由服务端拒绝其余命令;`rwx-cli.py` 是否受影响;现有「端口命令录制」(`--evaluate` 下的 commands-*.txt)在哪实现、记录哪些字段、如何扩展出墙钟/class/别名。
R2 启动参数:`--evaluate` 现在如何解析与生效;如何支持 `--evaluate <CASE>` 与 `--evaluate=<CASE>` 且无值时保持旧行为;与 `--headless`、`--data-dir`、端口文件的交互。
R3 `ai/custom/micro` 与 `ai/custom` 的现有内容及扩展点;内置 AI 如何给 team 2 设置「锁定目标=我方 + attack_move」,会不会持续重发指令、自行生产/建造/撤退/投降,如何让它只做 attack_move;AI 产生的命令是否进入 replay。
R4 单位与战斗机制:核对 §1 数值;`shieldDefectionMultiplier` 的精确语义(护盾与 HP 各吃多少伤害);双方能否边走边射;机甲 warmup 的重置条件(停火多久、换目标、move/stop/holdFire 各自是否重置);猛犸在 attack_move 下「目标进入射程是否停步开火」「是否追击射程外的移动目标」「炮塔转向/开火前摇」;over-cliff 通行规则,以及猛犸面对悬崖另一侧目标时的行为(绕路/卡住/原地)。据此写一段「人类如何用 1 台机甲击败 1 台猛犸」的机制分析,推断部分标明未验证。
R5 `unit-list`/`unit-show` 现暴露的字段(hp、shield、武器冷却/warmup、当前目标、朝向、最近受击);敌方 order/target 是否可见;雾效关闭时是否全量可见。引擎内有而 CLI 未暴露的,评估可只读新增;引擎没有的,记录缺口,不要伪造。
R6 地图:现有随机地图/TMX 设施可否复用;程序化构造地图并加载的最短路径(含载入任意路径的 tmx);环湖 tmx 中指挥中心如何识别、移除、替换为单位;`map-path` 是否按 over-cliff 单位的通行类型寻路;`map-show` 图例中 -7 对机甲/猛犸分别意味着什么。
R7 胜负判定:队伍何时被判败;无建筑场景如何覆写为「某队单位全灭」;同 tick 双灭如何处理。
R8 时间控制:tick 时长;pause/step 的实现与最小步长;能否在 tick 回调里于精确 tick 边界暂停;step 是否全速执行、何时回执;录像期间暂停下令「排队到下一 tick」的机制,及如何在 units 输出里体现「已受理未生效」。
R9 现有 `--evaluate` 证据(telemetry.jsonl 等)记录了什么、时间分辨率;哪些可复用于 re-sim。
R10 确定性:地图生成与模拟各自的 RNG 来源;单位 id 分配是否稳定;同 (task, seed) 能否完全复现(含 AI)。
R11 构建/测试/headless 运行方式;本环境能否运行游戏(不能则标「未验证项」)。
R12 Replay:文件格式;是否记录所有队伍(含 AI)的命令;如何引用地图与初始单位(是否只存地图名);校验和机制;如何以编程方式在载入后立即开录并指定输出位置;现有 re-sim 入口(`evaluate_replay.py` 如何驱动 headless 重放);要在重放时复现「布景」(环湖替换、类型1生成图、AI 锁定)需要什么钩子;暂停/变速不入录像对重放节奏的影响。
R13 GUI 人类模式:不经战役室如何直接载入场景进入 GUI 对局;玩家如何开始/暂停;胜负如何展示;能否在开局后禁用暂停/变速;录像如何自动开录。

## 3. 已定设计决策(不要改;与代码冲突时在 NOTES.md 记录,选最接近的做法)
D1 分层:引擎只做「布景、生命周期、命令隔离、薄网关日志、自动录像、结果文件」,不计算指标;指标全在 `evaluate/micro/` 的外部脚本里算,且为插件式(§8)。
D2 启动:`--evaluate <CASE>`(如 `--evaluate CASE1-duel`)进入 micro case 模式,参数用 `--case-args k=v,k=v`:
  必填:`task=1_2_3`;`seed=<int>` 可省略（省略时随机生成并写入 meta）;agent 模式另需 `track=realtime|pause`。
  可选:`mode=agent|human`(默认 agent;human=GUI、玩家用鼠标操控 team 1、不做命令隔离、载入后暂停在 tick 0,玩家解除暂停即开局);
  `briefingOrders=allow|deny`(默认 allow,仅 realtime)、`briefingSeconds`(默认 180,仅 realtime,超时自动 begin)、
  `turnSeconds`(默认 2.0,仅 pause;取整到 tick,实际值写入 meta)、`wallLimitSeconds`(默认 1200)、`gameLimitSeconds`(默认 600)、
  `startTimeoutSeconds`(默认 600,期间无任何 Agent 命令则 outcome=error/no-agent 并退出)、`enemyDelaySeconds`(默认 0,实现代价很小才做)、
  `liveSnapshots=true|false`(默认 false)、`out=<dir>`。
  无值的 `--evaluate` 保持旧行为。支持 `--headless`;多实例靠 `--data-dir`/`--cli-port-file`。复用旧 `--evaluate` 的采集设施,不重复造轮子。
D3 命令隔离:agent 模式下命令服务只注册 `rwx-micro-*` + `rwx-ping` + `rwx-help`;其余一律在服务端返回 `error: unknown command`(不是仅靠文档隐藏)。`rwx-help` 运行时返回该 case 的 `commands.md` 全文。命令集由 case/track 声明式配置。命令输出不得泄露证据目录路径与评测内部配置,错误信息不带堆栈。沿用现有 CLI 的线程模型。
D4 生命周期:载入并布景完成 → 暂停在 tick 0(briefing)→ `begin` → running → ended。briefing 期可观察;realtime 且 `briefingOrders=allow` 时可下令(pause 赛道在任何暂停点都可下令)。结束判定自动进行;结束后冻结游戏、正常停止录像、写 `result.json`,CLI 继续响应 ≥30s 供 Agent 读到结局。墙钟预算从收到第一条 Agent 命令起算(含 briefing/暂停期);human 模式不受 wallLimit 约束,仅受 gameLimit。
D5 自动录像:布景完成后、tick 0 前自动开录,不依赖任何命令;录像存入 run 目录,并按现有工具的约束(如须在 `replays/` 内)补一份拷贝或链接。
D6 赛道:
  realtime = 1× 连续运行;不注册 `next`;不可变速。
  pause = 固定回合制:`turnTicks = round(turnSeconds / tick时长)`;`begin` 运行第 1 回合,`rwx-micro-next` 运行后续回合;每回合在引擎 tick 回调中于精确的 tick 边界自动暂停(不得用外部脚本经 CLI 轮询发暂停);回合全速执行(不 sleep),`next` 阻塞到回合结束才回执;暂停期间下的 B 类命令在下一回合第 1 个 tick 生效;Agent 没有 pause/step/speed。回合数不设上限,由墙钟/游戏时间上限约束。
D7 单次尝试:不暴露 restart、surrender、speed、observe-reset,以及房间/联机/回放/建造/生产/编组/自动化(auto/keep/pending)等任何命令。不写脚本基线、不写自动微操。
D8 场景:雾效关闭;初始资金 0;无任何建造/生产能力,AI 不得生产;地图、出生点、a/b、单位 id 与别名由 (task, seed) 确定性产生。
D9 别名:我方 M1..Ma、敌方 E1..Eb(按出生位置稳定排序);所有命令的 `--units/--target/--id` 同时接受别名与引擎 id;meta 里两者都记。
D10 输出目录:`evaluate/output/micro/<case>/<run>/`,`<run>=<case>_<task>_<track>_s<seed>_<yyyyMMdd-HHmmss>`(human 模式 track 段写 `human`)。

## 4. 引擎侧实现(`core/src/main/java/com/corrodinggames/rts/game/ai/custom/micro`;必要的注册钩子可放别处,保持最小)
建议结构(类名可调):`MicroCase`(SPI:id、参数校验、布景 `setup(meta)`、命令 profile、每 tick 回调、结束判定)、`MicroCaseRegistry`、`MicroCommandProfile`、`MicroGateway`(薄网关)、`MicroRunWriter`(meta/result/gateway)、`case1/*`(地图生成、出生、AI 锁定、CASE1 命令附加项)。
薄网关记录(`gateway.jsonl`,一行一条,字段保持精简):
  request:kind=request、seq、wallRecvMs、wallReplyMs(单调墙钟,相对 run 起点)、gameMs、tick、phase(briefing|running|ended)、turn(pause 赛道)、class(A|B|C,以命令注册表为唯一来源)、name、raw、status(submitted|partial|error|rejected)、error、units(B 类:别名:id 列表)、skippedDead、parent(batch 子命令指向父 seq)。
  lifecycle:kind=lifecycle、event(first-command|begin|turn|end)、wallMs、gameMs、tick、turn、reason。
不要记录解析后的参数、应用 tick 等 replay 里已有的信息。

## 5. CASE1 场景实现
先做地图类型2(环湖,已有 tmx,未知最少)打通 level2 纵切,再做类型1。
- 布景必须是 meta.json 的纯函数,并提供同一入口 `MicroCase.setup(meta)` 供 re-sim 重新布景(含 AI 锁定、单位 id/别名分配)。
- 类型1 生成器:严格按 README;种子确定性;生成的地图写成 `map.tmx` 存入 run 目录(重放时从此加载)。校验项(失败则用确定性的重试种子重选,设重试上限并记录次数):尺寸范围;扰动数量 4–8;区域互不交叉;两个出生区净空(建议半径≥8格内无水/山);用猛犸的地面寻路确认双方出生区地面连通;单位出生在草地;队形紧凑(间距约2–3格,朝向对方)。
- 类型2:按 §1 保留与替换;保留哪几个由种子确定。
- 敌方 AI:按 R3 结论实现「锁定我方 + attack_move」,确认不会生产/建造/撤退/投降;`enemyDelaySeconds>0` 时开局后延迟激活。
- 结束判定:敌方全灭=win;我方全灭=loss;同 tick 双灭=draw;墙钟或游戏时间任一达限=timeout。
- human 模式:GUI 载入同一布景与 AI;自动录像;结束时展示结果并写 result.json(agent 字段写 "human");若代价小,开局后禁用暂停/变速,否则在文档中要求人类 1× 且不暂停(回放不记暂停/变速)。

## 6. 命令集(命名空间 `rwx-micro-`)
分类 A 观察 / B 操作 / C 控制,与评估脚本一致(A 查询、B 操作、C 其他;C 不参与 A→B 周期)。
输出格式:不必沿用 `rwx-game-*` 的输出样式。先阅读现有输出,再为 micro 命令自行设计「紧凑、稳定、对 LLM 友好」的格式——场景很小(≤10 个单位),允许写死字段与顺序。要求:①一单位一行、列顺序固定;②一次输出足够决策,不需要再追问;③所有回执带 `t=<游戏秒>`,统一为 `ok|partial|error: <原因>`;④A 类命令提供可选 `--json`;⑤字段、单位(世界坐标/秒)、小数位在 `commands.md` 给出完整字段表,并用 golden 快照测试固定格式。
A 观察:
- `rwx-micro-state`:phase、游戏时间、剩余墙钟预算、双方存活数与总血量/护盾、track(pause 赛道另显示 turn 与 turnSeconds)。
- `rwx-micro-units [--owner self|enemy|any] [--units <sel>] [--compact] [--delta] [--json]`:委托 `unit-list`,强制全量(取消默认 HP<80% 过滤)。每单位含:别名、id、类型、x、y、hp/maxHp、shield/maxShield、order(idle|move|attackMove|attack+目标别名)、武器状态(取 R5 能提供的:冷却/warmup)、其当前攻击目标(能提供则给)、到最近敌方的距离、「我能打到谁/谁能打到我」。暂停期间已下令但未生效的命令必须可见。
- `rwx-micro-unit --id <别名|id>`:委托 `unit-show`。
- `rwx-micro-map [--x --y --half-width --half-height]`、`rwx-micro-path --unit <id> --x --y`:委托 `map-show`/`map-path`;默认即单字符 ASCII 地形图（自动迷雾遮罩），图例须标明机甲可翻越而猛犸不可通过的格子。
- (可选,低优先级)`rwx-micro-log [--seconds N]`:细粒度命中日志,弥补 `game-events` 的 5 秒去重。
B 操作(均支持 `--queue`;`--units` 接受别名/id/`all`(我方全部存活)/`--hp N` 筛选):
- `rwx-micro-move`、`rwx-micro-attack-move`、`rwx-micro-attack --target`、`rwx-micro-stop`、`rwx-micro-mode --mode holdFire|returnFire|aggressive|…`:委托同名 order 命令。
- `rwx-micro-batch "<子命令>; <子命令>; …"`:子命令省略前缀,同一 tick 生效,逐条回执;pause 赛道允许 `next` 作为最后一条。
- (可选,代价小再做)`move` 增加 `--away-from <单位|x,y> --dist <格>` / `--toward`。
C 控制:
- `rwx-micro-begin`:结束 briefing 开始游戏(realtime 开始连续运行;pause 赛道运行第 1 回合)。
- `rwx-micro-next [--quiet]`:仅 pause 赛道:运行恰好 1 个回合后自动暂停;默认回执后附压缩的 units 输出(`--quiet` 仅回执);realtime 赛道返回 `error: not available in realtime track`。
- `rwx-ping`、`rwx-help`。
通用:对已死亡单位下令按「跳过已死单位」处理(全部已死则 error),网关记录 skippedDead;错误信息要让 Agent 一眼看懂。

## 7. 证据契约(版本 `rwx-micro-evidence/1`;写进 `evaluate/micro/README.md`)
run 目录:
  meta.json       引擎写(布景完成时)
  gateway.jsonl   引擎写(薄网关,§4)
  result.json     引擎写(结束时)
  match.replay    引擎自动录制(类型1另有 map.tmx)
  replay/         离线:`replay.py` 解析出的底层命令(.jsonl/.txt/.summary.txt)
  engine/         离线:re-sim 产物 snapshots.jsonl、events.jsonl、playback.json(一致性校验结果)
  eval/           离线:metrics.json、report.txt、report.html
meta.json:schema、case、task、mapType、level、seed、track、mode、a、b、地图尺寸与哈希、出生点、全部单位(id、别名、team、type、初始坐标、maxHp、maxShield、武器射程/装填/伤害)、限制参数、turnSeconds/turnTicks、tick 时长、引擎版本/commit、replay 文件名。
result.json:outcome(win|loss|draw|timeout|error)、endReason、gameMs、wallMs、turns(pause 赛道)、双方终态(存活数、hp、shield、各单位终态)。不含 reference(基线由评测器附加)。
snapshots.jsonl / events.jsonl:格式统一,来自 re-sim 或 liveSnapshots 均可,评测器不区分来源。snapshots:默认每 250 游戏毫秒一帧,每单位 id、x、y、hp、shield、order、target、武器冷却/warmup(可得则记)、alive。events:damage(attacker、victim、hp 伤害、shield 伤害、之后 hp/shield、双方当时坐标)、kill、order(unit、类型、目标/坐标、source=player|ai|engine)。
时间约定:gameMs=游戏毫秒,wallMs=单调墙钟毫秒;坐标=世界坐标。字段可增不可删改;有变动必须升版本。

## 8. 外部评测(`evaluate/micro/`;语言与 `evaluate/utils` 一致,默认 Python 3.11+)
结构:
- `common/`:`Run` 载入器(读 meta/gateway/result/replay 解析结果/re-sim 证据;提供按时间查状态、存活、交战窗口等);`native_actions.py`(把 replay 底层命令种类映射成 move/attack-move/attack/stop/mode 等语义,未知种类保留为 other 并报告);`resim.py`(调用重放补采,生成 `engine/`);报告工具。
- `CASE1-duel/evaluate.py <run目录> [--resim auto|force|skip] [--baseline auto|none|<metrics.json>]`:
  1) 无 `engine/snapshots.jsonl` 且未 skip → 先 re-sim,并把重放终局与 result.json 对比,结果写入 `engine/playback.json`;
  2) 用 `evaluate/utils/replay.py` 解析底层命令,只保留 team 1 的玩家命令(排除 AI);
  3) 有 gateway.jsonl 时,把 B 类请求与底层命令按 (team、tick 窗口、种类、单位集合) 顺序匹配;未匹配/歧义如实报告,不猜;
  4) 自动发现 `metrics/*.py`(约定导出 `ID`、`TIER`、`compute(run)->dict`);单个指标报错不影响其他(记录错误);
  5) 输出 `eval/metrics.json`、`report.txt`、`report.html`:顶部两行结局/实时性摘要;一张时间线图(双方总 HP/护盾随游戏时间变化,叠加 B 类命令刻度与交战窗口,pause 赛道叠加回合边界);缺证据的指标标 N/A,不要崩溃。
- 必须同时支持两种输入:agent run(有 gateway)与 replay-only(人类基线:只有 replay + meta,无 gateway、无墙钟)。
- 人类基线:`CASE1-duel/baselines/index.json` 登记 (task, seed) → 人类 run 的 metrics.json(或 run 目录);`--baseline auto` 时在报告里并排给出 human 列(仅 T1)。基线由评测器写入 `eval/metrics.json` 的 `reference` 字段,不回写引擎产物。
指标分层(定义写进 `evaluate/micro/CASE1-duel/METRICS.md`;A/B/C、决策周期等口径先读 `evaluate_stage_1.js` 并保持一致;每个指标在 metrics.json 里标 tier 与数据来源):
  交战窗口:首次交火(任一方距离≤12格或首次伤害)到结束。
  T1 人类可比层(只依赖 team 1 底层命令 + re-sim 状态):
   - outcome:result、我方存活数、delta=敌方HP损失比例−我方HP损失比例(HP 与护盾分开计,护盾单列;口径待 R4 定稿)、清场用时(win)/存活时长(loss)。排序建议写进 METRICS.md（task041 已改为：win 优先，timeout 不高于 loss；evaluate.py 不折算总分）。
   - cadence:team 1 底层命令相邻间隔(p50/p90/max)、交战窗口内每分钟命令数、交战期最大无令间隔。
   - ENGAGE_LAG:首次交火→首条包含我方存活单位的 B 类命令的延迟(交战前已有命令则 PREEMPTED)。
   - LOWHP_LAG:单位 HP 下穿 50%/25% → 首条包含该单位的 B 类命令的延迟(阵亡前无响应=UNANSWERED)。
   - IDLE_IN_COMBAT:敌方存活、交战窗口内,己方单位 order=idle 且无当前攻击目标或近 2s 未开火,持续 ≥2s。
   - STALE_COMMAND:底层命令引用已死亡/不存在单位的次数。
   - FIRE_UPTIME:每台存活机甲在「射程内有存活敌人」的时间里处于开火状态的占比,以及开火中断次数。开火状态优先取武器状态字段,否则用「该单位最近一次造成伤害距今 ≤τ」推断(τ 默认 1000ms,可配置)。
   - FREE_FIRE:我方造成的伤害中,命中时攻击者在所有存活敌人射程之外的占比(射程取 meta;damage 事件已带坐标)。
   状态: ENGAGE/LOWHP 类指标状态统一为 ANSWERED|LATE|UNANSWERED|PREEMPTED,阈值做成配置。
  T2 Agent 独有层(依赖 gateway):A/B/C 条数;A→B 决策周期;B→A 等待;观察陈旧度(每条 B 命令距其前最近一次 A 的时间);「每个决策周期敌方多开几轮炮」(用 meta 里的猛犸装填时间);墙钟用量;error/rejected/skippedDead 比例。游戏时间与墙钟两种口径(决策时间=recvWallMs−上条 replyWallMs,剔除引擎/传输耗时);briefing 期命令单列。
  T3 pause 赛道:回合数;每回合墙钟(上次 next 回执→下次命令收到)p50/p90/max;每回合 B 命令数;交战期空回合比例;ENGAGE/LOWHP 以回合数计。注意 pause 赛道里底层命令的 tick 间隔无意义(同回合同 tick),METRICS.md 要写明。
  暂缓(不要实现,只在 METRICS.md「后续」里列出):脚本基线与归一化、RETARGET_LAG、集火度、跨局聚合与 P−R 对比汇总。
单测:用手写小型 fixture 覆盖(无命令、命令全在 briefing、胜利、全灭、pause 赛道、含 stale、replay-only、带 human 基线);数值须可手算验证。

## 9. 实施阶段(每阶段结束更新 `evaluate/micro/CASE1-duel/PROGRESS.md`:完成项/未完成/风险;无阻塞则直接进入下一阶段)
P0 调研 → NOTES.md。
P1 框架纵切:`--evaluate <CASE>`、case SPI、命令隔离、自动录像、薄网关、生命周期;用类型2 + level2 + 最小命令(state/units/move/attack-move/begin);验收:CLI 手打一局,得到 meta/gateway/result/replay。
P1b 重放一致性闸门:对 P1 的对局做 re-sim(含布景重建),比对 outcome、各单位终态 hp/shield、击杀 tick 序列;通过则以 re-sim 为状态证据主路径;不通过则记录原因并启用 liveSnapshots 回退(契约不变),继续推进。
P2 场景完整 + human 模式:类型2 全 level;类型1 生成器与校验;敌方 AI;结束判定/超时;`mode=human`。
P3 命令集 + pause 回合制 + `commands.md`:全部命令、batch、别名、`next`。
P4 外部评测:Run 载入器、native_actions、指标插件、报告、基线登记、fixture 单测;真实 agent 一局出报告;再用该局回放走 replay-only 路径验证人类基线流程。
P5 文档与收尾:`START.md`、README 的「评估指标」段、`evaluate/micro/README.md`、验收矩阵。

## 10. 文档交付物
- `evaluate/micro/CASE1-duel/commands.md`:CASE1 的完整命令说明,只写 `rwx-micro-*` 及 ping/help。结构:概述(命名空间、A/B/C、赛道可用性)→ 通用约定(坐标/时间/别名/选择器/回执语义/暂停与回合规则)→ 每条命令(语法、参数、示例、回执、常见错误)→ 输出字段表 → 地图图例 → 与完整对局命令的差异简表。每个示例都必须实际执行过并与真实输出一致。
- `evaluate/micro/CASE1-duel/START.md`:只写给 Agent,假定它完全不了解这款游戏,也不知道本评测。包含:
  ①任务与胜负、时间与预算、单次机会、两条赛道的差异、如何知道结束及结束后做什么;
  ②游戏基础(格子/世界坐标与坐标轴方向、HP/护盾/射程/装填/DPS/移速/视野的含义、move vs attack-move vs attack vs stop、排队、攻击姿态、单位会自动开火、地形与通行规则、warmup 概念);
  ③双方资料卡(数值表 + 事实陈述,如射程差、机甲可翻越山地、猛犸电击基本无视护盾、双方可边走边射、机甲停火后 warmup 重置;不写打法建议),外加 ≤5 行微操术语表(风筝、集火、拉扯、走位);
  ④怎么调用命令(与 `agent-operation/START.md` 的调用方式一致)、最小「观察→决策→行动」循环示例、输出字段解读;
  ⑤实时性须知:realtime——游戏不会等你,briefing 与 begin,用 batch 压缩往返;pause——每回合固定 x 秒后自动暂停,可不限时思考但墙钟有预算,用 `rwx-micro-next`(或 batch 末尾)推进,回合长度不可更改;
  ⑥规则与禁止事项(只用 commands.md 里的命令,不读取评测输出/源码/无关文件,不尝试绕过限制);
  ⑦常见坑(别名、已死单位、不加 --queue 会替换原命令、暂停期间下令下一 tick 才生效)。
  中文撰写,结构稳定后再考虑英文版。
- `evaluate/micro/README.md`:框架说明 + 证据契约 + 如何新增 CASE + 如何采集人类基线(启动 `--evaluate CASE1-duel --case-args mode=human,task=...,seed=...` → 1× 不暂停地打完 → `evaluate.py <run>` → 登记到 baselines/index.json)。
- 把 `evaluate/micro/CASE1-duel/README.md` 里空缺的「评估指标」写成对 METRICS.md 的 10–15 行摘要。

## 11. 验收
V1 构建通过、现有测试通过;`--evaluate`(无值)旧行为不变。
V2 `--headless --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=realtime` 能启动;`rwx-micro-state` 显示 briefing;`rwx-game-pause`、`rwx-game-restart` 等非白名单命令被服务端拒绝;realtime 赛道 `rwx-micro-next` 被拒;pause 赛道可用。
V3 手动(或一次性最小 Python 烟测,不是基线、不评分):下令→begin→打到结束,得到 meta/gateway/result/replay;`evaluate.py` 产出 metrics.json 与报告。
V4 确定性:同 (task, seed) 两次启动,meta.json 里地图哈希、出生点、a/b、单位 id 与别名完全一致。
V5 类型1 生成器:3 个 level × 200 个种子的生成测试,全部满足 §5 校验项,并统计重试次数。
V6 类型2:3 个 level × 多个种子,保留与替换的数量/类型正确。
V7 限时:把 wall/briefing 限制调到很小,验证 timeout 结局、自动 begin、证据完整、录像正常收尾。
V8 指标单测通过(§8 fixture)。
V9 文档中的所有命令示例已实跑核对。
V10 重放闸门:≥3 局(不同 task/seed,含类型1),re-sim 的 outcome、各单位终态 hp/shield、击杀 tick 序列与实时一致(见 playback.json),不一致须给出原因。
V11 pause 赛道:每回合恰好 turnTicks 个 tick(用 gateway lifecycle 与 replay tick 交叉验证);暂停期间下的命令在下一回合第 1 tick 生效;Agent 无法 pause/step/变速。
V12 人类基线路径:`mode=human` 能载入同一布景并自动录像(GUI 不可验证则标「未验证」,至少完成 headless dry-run);用任一 agent run 的回放走 replay-only 路径(去掉 gateway)评测成功,并通过 baselines/index.json 在报告中显示 human 列。

## 12. 工作方式
- 先做 P0 并写 NOTES.md,然后按 P1→P1b→P2→…→P5 推进;先竖切后铺开。
- 遇到下列情况先停下,在最终回复里提问:内置 AI 无法按 §1 的行为配置;类型1 的随机生成无法在合理改动内满足 README;设计决策与现有架构冲突、需要大规模重构。
- 下列情况不必停工,按回退继续并在最终回复里醒目报告:重放闸门不通过(启用 liveSnapshots);R4 表明「吊打」机制与预期不符;GUI 无法验证。
- 不 push、不改无关文件、不删现有测试。环境无法运行游戏时,明确列出「未验证项」。
- 最终回复包含:改动文件清单、启动命令样例、验收结果表(V1–V12)、重放闸门结论、R4 机制分析摘要、未验证项、需要我决定的问题。

## 13.补充：

暂时定义暂停的自动时间戳为100tick。然后启动支持读取总的conig.json文件传入复杂配置、参数.'evaluate\micro\config.json'.
