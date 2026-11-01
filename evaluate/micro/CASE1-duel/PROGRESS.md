# CASE1-duel 实施进度

更新时间：2026-10-30（task043：GUI 观战与重放闸门；task042：重整参与者文档的信息边界）。

## 当前状态

**P1/P1b 已通过，P2–P4 主路径实跑通过，P5 文档已补齐。** 环湖地图按用户 2026-10-30 定稿直接使用 team0（我方）/team1（敌方），其他单位一律删除，源 TMX 不改。

### task043 窗口观战（用户要求）

- 新增 `--window`：显式要求 GUI（去掉 `--headless` 本来就会开窗口），与 `--headless` 互斥；启动打印 `RWX_WINDOW enabled renderer=gui case=...`。pause 与 realtime 都可用，命令仍走同一端口。
- 修窗口下三处崩溃：
  1. 首帧即暂停（micro briefing）时 `LayerBufferManager.gridCells` 尚未建立，fog pass 空指针 → `renderFogOverlayAndCursorSelection` 先 `ensureInitialized()`。
  2. `begin` 触发原版 mission 结束流程，`StatsHistoryChart` 在无 GL 上下文的线程建纹理 → JVM abort；micro 自己判定胜负，`MissionEngine.c` 在 micro 模式下直接返回。
  3. 逐 tick 的死亡会产生 scorch mark（同样要 GL）→ 窗口模式下暂停帧也消费 `pendingGameThreadTasks`，CLI 的模拟工作固定在渲染线程执行；已在渲染线程上时直接内联，避免自等待。
- 观战：pause 模式下布景完成与每个回合开始时把镜头对准我方单位；realtime 与 `mode=human` 不跟随。
- 类型1 随机图：尺寸 80–120 格；悬崖 5–10 个 + 水域 5–10 个（先定数量再合并撒点），第一个中心固定在我方出生点朝敌方 1/3 或 1/4 处、必为水域，其余中心用 Best-candidate 采样（目标间距 0.65√(WH/N)、带 ±15% 抖动、离出生点 8 格），每块用随机边界生长长出 10–20 格（每次从边缘集合随机挑一格扩张，非 FIFO），区域边界间距 ≥5 格；悬崖按"远离重心"取 8 朝向之一（gid 200+facing），LAND（猛犸）不可进入、OVER_CLIFF（机甲）可通过。旧实现是 BFS 8 格空心环，中间仍是草地。
- 时间上限：CASE1 的游戏时间上限改为 10 分钟（`gameLimitSeconds` 默认 600，原 1800）；墙钟思考预算仍是 1200 秒，两者任一达限即 `timeout`。
- 人类模式：`Q` 键切换暂停，走渲染循环的输入 drain（原版暂停键在 `gameLoop` 内，暂停后无法恢复）。
- 结算：GUI/人类模式按结局播放原版胜利/失败结算画面（`startGameEndSequence` / `endGameSequence`），`15` 秒倒计时后退出；headless 打印 `RWX_MICRO_RESULT` 后立即退出。GUI 结算期间 `gameSpeed` 置 1，否则暂停帧只画世界不画 HUD。
- 指标：击杀 tick 容差从“仅末杀 ±1”改为“每个击杀 ≤2 tick”，身份/顺序/终态仍须完全一致，warning 列出具体偏差（旧规则会把中间击杀的 2 tick 偏移判为通过）。
- 验证：pause（48 回合、含 B 命令）与 realtime 各一局在窗口下打完，`result.json`/`match.replay` 正常；两局 re-sim `consistent=true`（pause 有一条 2 tick skew 警告）。`:core:test` 29 套件 249 项、Python 13 项通过。

### task042 参与者文档整理（用户要求）

- 正式只分发 `START.md` 和 `commands.md`。START 自足说明启动、目标、无加权总分、task 三段编码、赛道/预算、公开属性和基础规则；commands 定义操作协议。
- 删除风筝/地形解法、击杀时间与集火推导、敌方决策周期/后撤阈值、源码变量和评价器事件/审计阈值。敌方只保留接敌、攻击、交战尝试拉开距离的外部行为说明。
- 补齐 attack 自动追击/目标死亡后的切换或重选、29 条有效订单上限、队尾合并、pending 与订单队列区别、stop/mode 与姿态的关系、不可达的替代路径/停留/重寻路语义。
- 补齐预热进入条件、渐退与恢复、机甲弹药追踪/160 tick 寿命/不因飞出射程销毁的规则；不推导具体战术能否成功。
- 纠正 task041：质量实际参与软挤压；猛犸足盾命中是 130 盾与 117 HP 双池扣减，不是“130 守恒分配/10% 进入 HP”。METRICS 中对应术语同步纠正，算法不变。
- 隔离实跑 51 请求：帮助全文与源文件一致，章节 2/目录可访问；attack 射程外发生移动；holdFire/returnFire 均阻止显式攻击开火，切 onlyInRange 恢复。仅验证规则，未制作策略/评分基线。
- 验证：`:core:test` 29 套件 248 项全过；`:desktop:headlessArchive` 成功；最终打包手册与文件逐字节一致；文档链接、代码围栏和信息披露检查通过。临时产物已清理。

### task040 调整（用户要求）

- 所有 micro 命令改为 `rwx-micro-1-*` 命名空间；`rwx-ping` 保留；裸 `rwx-micro-*` 与 `rwx-help` 均返回 `error: unknown command`。
- 新增 `rwx-micro-1-help`，复用 `rwx-help` 的编号章节解析（全文 / `--list [depth]` / 按编号或标题选取），绑定本 case 的 `commands.md`，错误提示写 `rwx-micro-1-help --list`。
- pause 赛道默认回合长度 60 tick（引擎时钟 `t=0.960`，名义 1 游戏秒；原 100 tick）；`turnSeconds = turnTicks/60` 是名义值。
- 核对 realtime：布景完成后游戏停在 tick 0（即 briefing），不推进游戏时间；`begin` 后才从 tick 0 起 1× 运行。实测 1.2s 内 tick 保持 0。

### task041 文档校准（用户审查）

- 时间：明确 1 tick = 16 game-ms（`16.666…` 被 int 截断）；`t=gameTimeMillis/1000`，60 tick → `t=0.960`；`turnSeconds` 是名义值；`meta` 增补 `gameMsPerTick`/`nominalTickMs`。
- 资料卡：猛犸装填 50 tick + 预热 30 tick = 80 tick/发（实测 1280 game-ms），DPS 97.5/60 tick；机甲 34→15 tick、满预热 220/60 tick；删除 92→170、163 等旧数值。当时“10% 穿盾”的表述已由 task042 纠正为实际双池扣减；击杀推导不再分发给参与者。
- level 范围：level1 `a=b+1,1≤b≤4`；level2 `a=b,1≤a≤4`；level3 `b=a+1,1≤a≤4`；level4 `b=a+2,1≤a≤3`；a/b 最大 5 但不同时为 5。
- `move`：默认姿态下仍会向射程内敌人开火，只是不停步/不追击；`mode` 六种语义与索敌半径补表。
- `mode`：与其它 B 命令一样下一 tick 生效，不写 waypoint/不清空队列，replay 记 `attack_mode` 行。
- 指标：PREEMPTED 改为“交火时 `order != idle` 才算”，避免 briefing 里早已走完的 move 误伤；指标增加 `evidence.source/verified`，区分 re-sim 与 live；ENGAGE_LAG 明确起点、生效 tick 与 pause 量化；timeout 不再声称高于 loss。
- 启动：START.md 开头新增可直接复制的 `:desktop:run --evaluate CASE1-duel` 命令与 `rwx-cli.py` 连接方式，正式测试只给 START/commands 也能开局。
- 客户端：`rwx-cli.py` liveness 接受 micro 的 `ok: pong...`；否则 `--port-file` 会把正常 micro 端口判成 stale。
- 指标：`normalize` 使用 replay 的真实 `game_time_ms`（旧代码按 `(tick+1)*16` 估算，差 16ms）；护盾损失计入死亡单位剩余护盾。

| 阶段 | 状态 | 结论 / 剩余 |
|---|---|---|
| P0 | 完成 | R1–R13 见 [NOTES.md](NOTES.md)（P0 快照，已标注哪些是历史）；HEADLESS 烟测通过；core:test 28→29 套件 |
| P1 | 通过 | 类型2/level2/seed1/pause：tick0 briefing、白名单、待发命令、60 tick 回合、54 回合 loss、四产物齐全 |
| P1b | 通过 | 4 局 re-sim：1_2_2 loss、1_2_1 win、1_1_2 loss、task040 的 1_2_2/60 tick loss；outcome/终态 hp+shield/击杀序列一致 |
| P2 | 主路径通过 | 类型2 全 level×2 seed、类型1 全 level、600 种子生成单测、human 载入 dry-run、4 类限时结局；task043 起 agent 局可在 `--window` 下实跑（pause/realtime 各一局打完并过闸门） |
| P3 | 通过 | 全命令/别名/batch+next/pause 60 tick/暂停命令下一回合首 tick/实时赛道拒绝 next/非白名单与旧命名空间拒绝；commands.md 示例均实跑；task041 复跑 `help`/`mode`/`stop --queue` 回执 |
| P4 | 通过 | Run/resim/native 映射/指标插件/报告；replay-only 路径、baseline auto 机制；Python fixture 13 项 |
| P5 | 完成 | START.md、commands.md、METRICS.md、micro README、CASE1 README 指标摘要 |

## 已确认的敌方策略

用户已允许通过已有 AiStrategy 增加 CASE1 策略，关闭全部原版宏观阶段，并要求每台交战猛犸沿远离当前机甲目标的方向退到自己的射程边界。前进用原生 attackMove；因攻移射程内会停车，后撤用原生 move，全部经过原生 CommandController 入录像。地形/边界不可达时只尝试可达的同向退点。

## 重放闸门结果

| run | task/seed | outcome | 终态 | 击杀 tick | checksum |
|---|---|---|---|---|---|
| `CASE1-duel_1_2_2_pause_s1_20261030-102343` | 1_2_2/1 | loss | 一致 | 1256 / 1830 / 2454 一致 | frame 1129、1501 各差 55，随后对齐 |
| `CASE1-duel_1_2_1_pause_s1_20261030-104507` | 1_2_1/1 | win | 一致 | 末杀 live 4912 / resim 4911（±1） | 0 |
| `CASE1-duel_1_1_2_pause_s7_20261030-104732` | 1_1_2/7 | loss | 一致 | 1547 一致 | 0 |
| `CASE1-duel_1_2_2_pause_s1_20261030-141759`（task040，60 tick/回合） | 1_2_2/1 | loss | 一致 | 2214 / 3235 一致 | 0 |
| `CASE1-duel_1_2_2_pause_s1_20261030-171629`（task043，**GUI**，48 回合，有 B 命令） | 1_2_2/1 | loss | 一致 | 1292 一致；1002 live 1968 / resim 1966（skew 2）；末杀 2864 一致 | 0 |
| `CASE1-duel_1_2_2_realtime_s1_20261030-171941`（task043，**GUI**，realtime） | 1_2_2/1 | loss | 一致 | 全部一致 | 0 |

- 两条 GUI 局都过了闸门：窗口模式下 `begin`/`next` 在渲染线程执行，replay 仍能复算出同样的 outcome 与终态。

- 终态 HP/护盾逐单位比对通过；outcome 一致。
- task040 的 60 tick 局闸门全新跑了 re-sim：checksum 0、warnings 0、击杀序列一致；说明此前 1_2_2 的瞬时帧差后来未再出现。
- 历史 checksum 告警只出现在 task039 的 1_2_2 局，且换 team 编号、去掉每 tick 额外 `pathfindingEngine.a(1f)` 后数值完全相同，判断为暂停录制与重放之间某一发 55 伤害的采样帧差；不影响终态/击杀，只作 warning。
- 击杀 tick 容差：身份/顺序/终态必须完全一致，每个击杀允许 ≤2 tick 偏差，`playback.json.warnings` 逐条列出偏差（task043 之前只容忍末杀 ±1，会用“前缀单位相同”掩盖中间击杀的偏移）。

## 已修复的关键问题

- resim 读不到录像：回放进程 cwd 在仓库根，而副本在隔离目录；改为 cwd=隔离目录、`-Dlaunch.dir=仓库根`。
- 生成地图缺 `Items` 层导致地图加载失败/Replay 统计数组空指针：`Case1Map.write` 补空 Items 层。
- 无建筑场景引擎把 team 标成 `isTeamDefeatedTech`，开局后所有订单被拒：每 tick 在 `MicroRuntime.afterTick` 清除该锁存（仅 micro 模式，不改普通对局语义）。
- `--micro-config evaluate/micro/config.json` 实跑通过（CASE/task/seed/track 来自配置文件，task=1_2_2, seed=1, team0/1）。
- 停止命令在 Replay 里 `type=null`：`native_actions` 把 `clear_existing_orders` 映射为 stop。
- task040：命令命名空间统一为 `rwx-micro-1-`；`rwx-help` 不再注册，改由 `rwx-micro-1-help` 读取本 case 的 `commands.md`（复用编号章节解析）；pause 默认回合改为 60 tick；实测确认 realtime 的 briefing 就是“布景后停在 tick 0”。
- task041：时间/DPS/护盾/level/move/mode/指标口径按代码与实测校准；START.md 增加自足的启动章节；`PREEMPTED` 只在交火时仍非 idle 时成立；`metrics.json.evidence` 标注 re-sim 或 live。
- task043：`--window` 窗口观战；暂停帧消费游戏线程任务、micro 模式下屏蔽原版结束流程、fog 缓冲惰性初始化；击杀 tick 容差改为每杀 ≤2 tick。

## 风险与未验证

- GUI 人类模式：`mode=human` 已能载入、暂停 tick0、写 meta/自动录像；鼠标操控、胜负展示、禁止暂停/变速未实机验证（task043 验证的是 agent 局 + 窗口，不是人类操作）。
- 类型1 只实跑 1 个种子；600 种子覆盖的是生成器几何与连通性，不是每张图的实战。
- `enemyDelaySeconds` 只实现参数与策略入口，没有实跑延迟激活。
- task039 的 checksum 帧差未定位到具体单位/字段；task040 新局未复现，结论是终态与击杀不受影响。
- 指标 fixture 覆盖手算场景，但真实 Agent 长局样本仍少；跨局聚合/归一化按要求暂缓。
- task043 起 GUI 局的闸门已补上（见上表两条 GUI run）；`--dist` 必须指向用当前源码构建的 Headless 包，`desktop/build/headless` 可能是旧包，会让 playback 卡在 `end of replay block` 而不写 `final.json`。
- 本地 `evaluate/output/micro` 与 `replays/CASE1-*` 已清理；上表 run 名是历史记录，需要复算时重跑。

## V1–V12

| 项 | 状态 | 证据 / 限制 |
|---|---|---|
| V1 构建/回归/旧 evaluate | 通过 | core:test 29 套件 249 项全过；Headless archive 构建；无值 `--evaluate` 有单测 |
| V2 启动/白名单/赛道 | 通过 | pause/realtime 实跑；`rwx-game-*`/`rwx-help`/旧 `rwx-micro-*` 均 unknown；realtime `next` 拒绝 |
| V3 对局/四证据/报告 | 通过 | 多局四产物 + `eval/metrics.json/report.txt/report.html` |
| V4 同种子布景确定性 | 通过 | 1_2_2/1_1_2 各两次启动，meta 的 a/b/units/map sha 等完全一致 |
| V5 类型1 六百种子 | 通过 | `MicroCaseTest` 3 level×200 seed，尺寸/区域/出生净空/LAND 连通/重试 |
| V6 类型2 全 level | 通过 | 1_2_1/2/3 × seed1,7：a/b、数量、类型、team0/1、删除中心数正确 |
| V7 限时/自动 begin/收尾 | 通过 | wall-limit、game-limit、briefing 自动 begin、no-agent error 四例 |
| V8 指标 fixture | 通过 | `python -m unittest discover -s evaluate/micro/test` 13 项 |
| V9 文档示例 | 通过 | commands.md 示例逐条实跑；`rwx-micro-1-help` 返回新文档，`--list` 列 6 章 |
| V10 重放闸门 | 通过 | 2 类型2 + 1 类型1 + task040 的 60 tick 局；本地 run 目录已清理，复算需重跑 |
| V11 精确回合 | 通过 | lifecycle turn 差 60；暂停下令在下一回合首 tick(60) 生效；realtime briefing 停在 tick 0；无 pause/step/speed |
| V12 human/replay-only | 部分 | human 载入 dry-run、replay-only 评测、baseline auto 机制通过；真实 GUI 人类一局未验证；`mode=human` 未强制禁暂停/变速 |
