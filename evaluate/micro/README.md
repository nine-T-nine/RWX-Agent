# 微操试验场（micro cases）

小规模、固定场景、可复现的实时/回合制操控评测。引擎负责布景、生命周期、命令隔离、薄网关、自动录像和结果文件；指标全部在 `evaluate/micro/` 的 Python 脚本里离线计算。

## 1. 启动

从仓库根目录启动

```powershell
# agent，pause 赛道；headless 发行版用 :desktop:run --args
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=pause --data-dir=agent-operation/tmp/micro-a --cli-port-file=agent-operation/tmp/micro-a/port'

# 人类模式（GUI，同一布景，载入后暂停在 tick0；解除暂停即开局）
.\gradlew.bat :desktop:run '--args=--no-steam --evaluate CASE1-duel --case-args mode=human,task=1_2_2,seed=1'

# agent 对局但开着窗口观看（--window 与 --headless 互斥；pause/realtime 都支持）
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE1-duel --case-args task=1_2_2,seed=1,track=realtime --data-dir=agent-operation/tmp/micro-a --cli-port-file=agent-operation/tmp/micro-a/port'
```

- `--window` 显式要求 GUI（不带 `--headless` 时本来就是窗口）。窗口模式下游戏线程由渲染循环驱动，暂停帧也会消费 CLI 排入的游戏线程任务，因此暂停时的命令与回合推进不会在无 GL 上下文的线程上碰图形；pause 模式下镜头在每个回合开始时对准我方单位；realtime 与 `mode=human` 不跟随。关闭窗口即退出进程。
- micro 模式下不运行原版 mission 的胜负流程（结果由 `MicroRuntime` 写 `result.json`），所以窗口里不会弹出原版结束界面。

- `--evaluate` 不带值保持原有完整对局行为。
- `--case-args k=v,k=v`；必填 `task`、`seed`，agent 模式另需 `track=realtime|pause`。
- 可选：`mode=agent|human`、`briefingOrders=allow|deny`（仅 realtime）、`briefingSeconds`（仅 realtime）、`seed`（可选，省略即随机并写入 meta）、`turnSeconds`/`turnTicks`（pause 默认 60 tick；`turnSeconds` 是**名义**秒，60 tick 的引擎时钟是 0.960 秒）、`wallLimitSeconds`、`gameLimitSeconds`、`startTimeoutSeconds`、`enemyDelaySeconds`、`liveSnapshots`、`out`。
- 布景完成后游戏停在 tick 0（briefing），realtime 与 pause 都一样；realtime 的 `begin` 从 tick 0 起 1× 运行，pause 的 `begin` 跑第 1 回合。
- 1 tick 名义 16.667ms，但引擎 int 截断后 1× 每 tick 只加 **16 game-ms**：60 tick → `t=0.960`、`gameMs=960`。`tickDurationMs`/`gameMsPerTick=16`，`simulationFrameMs`/`nominalTickMs=16.667`。
- 启动完成后日志出现 `RWX_MICRO_RUN <run目录>` 与 `RWX_MICRO_READY`，端口写入 `--cli-port-file`；命令客户端沿用 `agent-operation/tools/rwx-cli.py`。
- 人类模式：`Q` 键切换暂停（第一下从 briefing 开局，之后暂停/继续）。原版暂停键在 `GameUI.updateInput` 里处理，而暂停帧不会驱动 `gameLoop`，所以它只能暂停、不能恢复；`Q` 走渲染循环，双向都有效。\n- 结束行为：headless 打印 `RWX_MICRO_RESULT ...` 后直接退出进程；`--window` 或 `mode=human` 播放原版胜利/失败结算画面，`RWX_MICRO_EXIT_IN 15..1` 倒计时后退出。
- 配置文件 `evaluate/micro/config.json` 可提供默认值（`--micro-config`），命令行参数覆盖它。

## 2. 证据契约（`rwx-micro-evidence/1`）

```
<run>/
  meta.json        布景完成时写
  gateway.jsonl    薄网关，一行一条（请求/生命周期）
  result.json      结束时写
  match.replay     自动录像（类型1另有 map.tmx）
  replay/          离线：replay.py 解析出的底层命令
  engine/          离线：re-sim 的 snapshots.jsonl/events.jsonl/final.json/playback.json
  eval/            离线：metrics.json/report.txt/report.html
```

### meta.json

`schema`、`case`、`task`、`seed`、`mapType`、`level`、`track`、`mode`、`agent`、`controlledTeam`、`map{file,sha256,width,height,tileSize}`、`limits{wallSeconds,gameSeconds,briefingSeconds,startTimeoutSeconds,enemyDelaySeconds}`、`turnTicks`、`turnSeconds`、`tickDurationMs`（16，实际 game-ms/tick）、`simulationFrameMs`（16.667，名义）、`gameMsPerTick`（16）、`nominalTickMs`（16.667）、`engineVersion`、`commit`、`replay`、`enemyPolicy`、`liveSnapshots`、`a`、`b`、`units[]`。

每个单位：`id`、`alias`、`team`、`sourceTeam`、`type`、`x`、`y`、`maxHp`、`maxShield`、`weapons[{index,range,damage,reloadFrames,warmupFrames,warmupNoReset,reloadTransferFrames}]`、`range`。`range`/`weapons[].range` 是中心距；`reloadTransferFrames` 是**出生时**预热对应的装填减免（机甲/猛犸出生都是 0；机甲满预热最大 19，来自 `warmupShootDelayTransfer`）。布景是 meta 的纯函数，re-sim 用同一份 meta 重建。

### gateway.jsonl

请求行：

```json
{"kind":"request","seq":14,"wallRecvMs":120,"wallReplyMs":125,"gameMs":0,"tick":0,"phase":"briefing","turn":0,"class":"B","name":"rwx-micro-1-move","raw":"...","status":"submitted","error":null,"units":["M1:1000"],"skippedDead":[],"parent":null}
```

生命周期行：

```json
{"kind":"lifecycle","event":"turn","wallMs":900,"gameMs":960,"tick":60,"turn":1,"reason":"boundary"}
```

不记录解析后的参数和应用 tick（这些在 replay 里）；`class` 以命令注册表为唯一来源；`status` 为 `submitted|partial|error|rejected`。

### result.json

`outcome`（`win|loss|draw|timeout|error`）、`endReason`、`gameMs`、`tick`、`wallMs`（相对首条命令）、`turns`、`kills[]`、`units[]`（终态）、`self`/`enemy`（存活数、hp、shield）。不含基线，基线由评测器附加到 `eval/metrics.json.reference`。

### snapshots/events

- `snapshots.jsonl`：默认每 250 游戏毫秒一帧，`gameMs/tick/units[]`，单位含 `x,y,hp,shield,order,target,weapons,alive`。
- `events.jsonl`：`damage`（attacker/victim/HP 与护盾伤害/之后数值/双方坐标/当时所有对手位置与射程）、`kill`、`order`（`source=player|ai|engine`）。
- 两个来源（live fallback 与 re-sim）格式相同；评测器用 `engine/playback.json` 判断是否走了 re-sim，并在报告里标 `evidence=re-sim|live/unverified`。字段只增不删，变动必须升 schema 版本。

## 3. 评测

```powershell
python evaluate/micro/CASE1-duel/evaluate.py <run目录> [--resim auto|force|skip] [--baseline auto|none|<metrics.json>] [--metrics-config <json>]
```

1. 没有 `engine/snapshots.jsonl` 且未 skip 时先 re-sim；把重放终态与 `result.json` 比对，写 `engine/playback.json`（outcome、各单位终态 hp/shield、击杀身份与顺序；每个击杀 tick 允许 ≤2 tick 偏差并记 warning，超出判失败）。`metrics.json.evidence.source` 为 `re-sim` 且 `verified=true` 才代表过了独立复算闸门。
2. 用 `evaluate/utils/replay.py` 解析底层命令，只保留 `meta.controlledTeam` 的我方命令。
3. 有 gateway 时按 (tick 窗口、种类、单位集合) 顺序匹配 B 请求；歧义/未匹配如实报告。
4. 自动发现 `metrics/*.py`（导出 `ID`、`TIER`、`compute(run)`），单指标异常不拖垮其它。
5. 生成 `eval/metrics.json`、`report.txt`、`report.html`（结局/实时性摘要 + 双方总 HP/护盾时间线 + B 类命令刻度 + 交战窗口 + pause 回合边界）。

`--resim` 需要 Headless 发行包（`--dist`，默认 `desktop/build/headless`）和 Java（`--java` 或 `JAVA_HOME`）。re-sim 进程在隔离目录的 `replays/` 里读副本，不污染仓库 `replays/`。

- **live fallback**：`liveSnapshots=true` 时引擎在跑的过程中直接写 `engine/snapshots.jsonl`/`events.jsonl`；`--resim auto` 发现它们已存在就**跳过 re-sim**，因此没有 `playback.json`、没有独立复算，报告显示 `evidence=live/unverified`。它只用于调试/没有 Headless 包的场景；**正式评测用默认 `liveSnapshots=false` + re-sim**。
- **re-sim 失败**：`evaluate.py` 捕获异常写入 `diagnostics.resimError`，不会伪造 `playback.json`；如果因此没有快照，T1 指标会 N/A 或 `ERROR`。报告仍会输出 `outcome`（来自 `result.json`）并标注 `evidence=live/unverified`。`--resim skip` 同理。

## 4. 新增一个 CASE

运行时不再认识任何 case id：它只通过 `MicroCase` / `MicroSession` 两个接口拿东西。新增一个 CASE 只需在 case 自己的类里声明，不改 `MicroRuntime`、`MicroConfig`、`MicroCommandProfile`。

### 4.1 Java 侧（`core/src/main/java/.../micro/`）

1. **实现 `MicroCase`**（`<caseN>/CaseN<Name>.java`），必须实现：
   - `id()`、`prepareMap()`、`layout()`、`setup()`、`controlledTeam()`；
   - `taskInfo(task)`：**自己校验 task 语法**，返回 `TaskInfo(mapType, level, gameSeconds, wallSeconds)`（预算也在这里，`MicroConfig` 不再硬编码）；
   - `helpCommand()`：返回本 case 的帮助命令名（如 `Command.TRANSPORT_HELP.commandName()`），runtime 用它渲染 `commands.md`；
   - `profile(config)`：用 `new MicroCommandProfile(config.track, COMMANDS)` 声明本 case 接受的命令（`COMMANDS` 常量数组放在 case 类里），**命令归属只写在这一处**。
2. **可选覆盖**（默认值即"常规 case"）：
   - `startsRunning()`：true = 布景后直接 1× 运行，没有 begin/pause/step（CASE3）；
   - `requiresAgentRealtime()`：默认 true；只有支持回合制的 case（CASE1）返回 false；
   - `reportsScenarioFailure()`：布景失败时是否仍写 `meta.json`/`result.json` 再退出；
   - `fallbackEnemyTeam()`：`layout()` 只列出一个队伍时的敌方队号；
   - `session(run, meta)`：返回本 case 的 `MicroSession`（几乎都要实现）。
3. **实现 `MicroSession`**：命令处理 + 原生命令授权 + 证据。
   - 继承 `AbstractCaseSession` 可白拿 `stamp()/emit()/reject()/permitted/allow()/recorded()/applied()/violations.jsonl`，只需实现 `matches(nativeCommand, instruction)`（每种指令允许的原生命令形状）、`nativeLog()`（native 记录写哪个文件）、`closeLogs()`；
   - `execute(command, args)` 里自己解析参数、自己创建原生命令并登记到 `permitted`；
   - 需要引擎事件时覆写 `damage()/created()/waiting()/afterTick()`；
   - 不接受某条命令时，默认回 `unknown command`；有自己的拒绝语义就覆写 `rejectCommand()`；
   - 若本 case 刻意复用完整对局的 order 服务（CASE1 的 `MicroCommands`），覆写 `acceptsServiceOrders()` 返回 true，否则 `RwxEngineLocalGameService` 会把通用 order 当成越权拒绝。
4. **注册**：`MicroCaseRegistry.find()` 加一行 `if`。这是唯一允许出现 case id 字符串的 Java 位置（`MicroConfig` 用它校验 id）。
5. **CLI**：`RwxCliCommands` 加命令枚举（`Domain.MICRO`），`MicroCommandProfile` 的静态 `ENTRIES` 补上分类（A/B/C）。命令名带 `rwx-micro-<n>-` 前缀只是命名约定，运行时不再靠前缀判断归属。

### 4.2 评测侧（`evaluate/micro/<CASE>/`）

1. 文档：`README.md`、`commands.md`（会打进 core jar，供本 case 的 `rwx-micro-<n>-help` 读取；文件名也兼容 `command.md`）、`START.md`、`METRICS.md`。`commands.md` 的一级章节必须写成 `## 1. 标题` 这种带编号的形式，help 的 `--list`/章节选取才可用。
2. `evaluate.py` + `metrics/*.py`：每个插件声明 `ID/TIER/SOURCES` 并提供 `compute(run)`，插件只读证据文件、不参与游戏；可复用 `common/` 的 replay/resim/统计工具。
3. `baseline*.py`：只用本 case 的公开命令（见 `START.md` 白名单），不读 `meta.json` 私有字段、证据或 Replay。
4. `test/smoke.py`：至少覆盖基线取胜、缩短预算 → timeout、无 Agent → error、越权命令被拒、同 task+seed 布景可复现。

### 4.3 验收

重新构建 Headless 包并实跑：`meta/gateway/result/replay` 四产物齐全，`engine/*` 证据与 `violations.jsonl` 行为符合本 case 约定，`test/smoke.py` 通过，`rwx-micro-<n>-help` 能列出章节。

## 5. 人类基线

1. `--evaluate CASE1-duel --case-args mode=human,task=...,seed=...` 启动 GUI，载入后暂停在 tick 0。
2. 人类玩家用鼠标操控 team0，解除暂停后 1× 打完（规则上不暂停/不变速）；自动录像。
3. 对局结束写 `result.json`；`evaluate.py <run目录>` 生成 `eval/metrics.json`。
4. 在 `evaluate/micro/CASE1-duel/baselines/index.json` 登记 `{"runs": {"<task>:<seed>": "<相对路径或 run 目录>"}}`。key 是 **task:seed**，每个要比较的组合都要有自己的人类 run，不能拿别的 seed 顶替。
5. 之后带 `--baseline auto` 的 run 会在报告中并排显示 human 的 T1 指标；基线只写 `eval/metrics.json.reference`，不回写引擎产物。

- 人类只有 realtime 1×；和 pause 赛道的 Agent 比较时，`outcome`/`hpLossFractions`/`ENGAGE_LAG`/`LOWHP_LAG`/`FIRE_UPTIME` 可参考（pause 有回合量化），`cadence`/`turn`/`thinkingWallMs` 不可比。realtime 与 pause 不共享榜单。
- `delta` 只用于同一 task+seed 内；`timeout` 不高于 `loss`，不要把它当胜利。
- GUI 在当前环境仍**未验证**：鼠标操控、胜负展示、暂停/变速拦截都没有实跑证据。`mode=human` 目前只把“解除暂停”当作开局，**没有强制禁用 GUI 的暂停/变速**；正式人类基线需要人工监督“1× 不暂停”，或后续补真正的锁。文档不伪造结果。

## 6. 目录

```
evaluate/micro/
  config.json              默认 case 参数
  common/                  Run 载入、replay 语义、resim、指标计算、报告
  test/                    Python fixture 测试（手算可核）
  CASE1-duel/              README、START、commands、METRICS、NOTES、PROGRESS
                           evaluate.py、metrics/、baselines/
```
