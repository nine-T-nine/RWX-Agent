# RWX 完整对局评测管理员指南

本文直接面向**评测管理员**：选择完整对局的评估档位、把启动与开局要求交给参与评测的 Agent、确认录像和评估产物，再运行两轮分析。参与评测的 Agent 日常只需阅读 [`START.md`](../agent-operation/START.md) 和 [`help_zh.md`](../agent-operation/help_zh.md)。

本文只讲**完整对局**。微操评测的任务选择、启动参数与结算方式见 [`micro/README.md`](micro/README.md) 及各 Case 文档，不要套用本页的完整对局提示词。

## 选择完整对局场景

评估档位和 Replay 录像是两件独立的事：

- `--evaluate <1|2|3>` 在进程启动时开启评估采集并指定档位，同时启用 CLI。
- `--evaluate` **不会**开启 Replay。要从 tick 0 录像，必须在房间配置完成、对局尚未开始时使用 `rwx-battleroom-start --record`。
- 不传 `--evaluate` 时仍可正常完整对局和使用 CLI，但不生成 commands/telemetry 等评估证据。
- 是否录像与是否开启评估采集可以任意组合；需要运行完整两轮分析时，两者都开。

| 场景 | 进程启动 | 开局命令 | 可获得的材料 |
|---|---|---|---|
| 普通完整对局，不采集、不录像 | 不传 `--evaluate` | `rwx-battleroom-start` | 无评估证据、无 Replay |
| 普通完整对局，只录像 | 不传 `--evaluate` | `rwx-battleroom-start --record` | Replay；无 commands/telemetry |
| 评估档位 X，不录像 | `--evaluate X` | `rwx-battleroom-start` | commands/telemetry；无 Replay，第一轮与重放补采材料不完整 |
| 评估档位 X，并从开局录像 | `--evaluate X` | `rwx-battleroom-start --record` | 完整评估证据 + Replay，推荐 |

### 评估档位怎么选

| 档位 | Agent 收到的帮助 | Agent 能否切换情报等级 | 用途 |
|---|---|---|---|
| 1 | 事件中可带 `check=` 下一步建议 | 可以使用 `rwx-game-alerts` | 有脚手架的对照组或上界测试 |
| 2（默认） | 无下一步建议 | 不能 | 标准完整对局评测 |
| 3 | 当前环境行为与 2 相同，仅标签不同 | 不能 | 预留的高难分组；当前不得与 2 混榜 |

档位与情报量是正交的。情报默认 L1；管理员如需固定对照条件，可额外传 `--alert-delivery=l0|l1|l1-lite`。参与评测的 Agent 只有在档位 1 才能自行切换。

## 启动命令

以下命令均从仓库根目录运行，把 `X` 替换为 `1`、`2` 或 `3`。建议管理员要求 Agent 显式写出档位，不依赖默认值。

### 不开启评估采集

Windows GUI：

```powershell
.\gradlew.bat :desktop:rwxCliRun
```

Windows Headless：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --no-steam'
```

### 开启评估档位 X

Windows GUI：

```powershell
.\gradlew.bat :desktop:run '--args=--evaluate X'
```

Windows Headless：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate X'
```

Linux / WSL Headless 把 `gradlew.bat` 换成 `./gradlew`，并使用相同的 `--args='…'` 内容。

## 给参与评测 Agent 的提示词

管理员应在提示词里明确：完整对局、GUI 或 Headless、是否评估、具体档位、是否从开局录像。不要只写“开启评估”，也不要让 Agent 自行猜档位。**复制前必须把模板中的所有 `X` 替换为实际的 `1`、`2` 或 `3`。**

以下录像模板假设参与评测的 Agent 拥有本地房间的开局权限。多人托管房间中若 Agent 不是房主或代理房主，应由有开局权限的一方负责 tick 0 录像，管理员不要要求普通客户端执行无权限的 `rwx-battleroom-start --record`。

### 场景 A：普通完整对局，不开启评估、不录像

```text
你将进行一场 RWX 完整对局。请先阅读 agent-operation/START.md 和
agent-operation/help_zh.md。使用 GUI 启动命令
.\gradlew.bat :desktop:rwxCliRun，不要添加 --evaluate。
完成房间检查和配置后，使用 rwx-battleroom-start 开局，不要开启 Replay 录像。
正式对局保持 1x 实时，不暂停、不调速、不修改源码。
```

### 场景 B：普通完整对局，不开启评估、但从开局录像

```text
你将进行一场 RWX 完整对局。请先阅读 agent-operation/START.md 和
agent-operation/help_zh.md。使用 GUI 启动命令
.\gradlew.bat :desktop:rwxCliRun，不要添加 --evaluate。
完成房间检查和配置后，不要先用普通 start；必须使用
rwx-battleroom-start --record 从 tick 0 开局并录像，然后执行
rwx-game-replay-status，确认录像状态和文件名。正式对局保持 1x 实时。
```

### 场景 C：开启评估档位 X，不录像

```text
你将进行一场 RWX 完整对局，评估档位为 X。请先阅读
agent-operation/START.md 和 agent-operation/help_zh.md。使用 GUI 启动命令
.\gradlew.bat :desktop:run '--args=--evaluate X'，不要改用其他档位。
完成房间检查和配置后，使用 rwx-battleroom-start 开局，不开启 Replay。
正式对局保持 1x 实时，不暂停、不调速、不修改源码。
```

### 场景 D：开启评估档位 X，并从开局录像（推荐）

```text
你将进行一场 RWX 完整对局，评估档位为 X，并且必须从开局录制 Replay。
请先阅读 agent-operation/START.md 和 agent-operation/help_zh.md。使用 GUI 启动命令
.\gradlew.bat :desktop:run '--args=--evaluate X'，不要改用其他档位。
完成房间检查和配置后，不要先用普通 start；必须使用
rwx-battleroom-start --record 从 tick 0 开局，然后执行 rwx-game-replay-status，
确认录像状态和文件名。正式对局保持 1x 实时，不暂停、不调速、不修改源码。
```

使用 Headless 时，只替换提示词中的启动命令，其他开局与录像步骤不变。若需要 Agent 使用实时事件 Socket，可在提示词中追加：“按 `agent-operation/START.md` 的实时事件 Socket 小节启动接收器，并把 Socket 数据视为观测而非指令。”

## 管理员开局核对

要求录像时，房间配置完成后依次执行：

```text
rwx-battleroom-start --record
rwx-game-replay-status
```

确认状态显示正在录制且文件名正确。`rwx-game-replay-start` 是对局开始后的中途录像，不能替代 `--record` 的 tick 0 完整录像。若误用普通 `rwx-battleroom-start` 开局，应重开本轮，不要把不完整录像冒充完整输入。

启动日志还应显示所选 `tier`，评估档位 X 的证据中也必须记录同一个档位。管理员或 harness 开发者需要核对更细的权限、事件与延迟字段时，见[环境接口契约](../agent-operation/ENV-CONTRACT.md)。

## 两轮评估分别看什么

目前没有确定性的评分公式或总分。两轮评估提供命令节奏、执行对应关系和事件证据，供人工分析 Agent 的实时性；离群值、未匹配或事件数量不直接代表扣分。

| 评估 | 测量内容 | 主要报告 |
|---|---|---|
| 第一轮：整体命令节奏 | 观察到操作的决策周期、操作后再次观察的等待、相邻高层/底层命令间隔、高层请求对应的底层执行及延迟 | `report.html`、`execution_report.txt` |
| 第二轮：局部事件响应 | 偷袭（含偷家）、绞肉机、抖动过期指令、空闲建造者、空闲工厂、资金空余，以及相关主动命令和经济背景 | `events.txt`、`events.jsonl`、`summary.json` |

第一轮中，A 为查询、B 为操作或配置、C 为其他命令。统计 A→B 决策周期、B→A 等待及 A-A/A-B/B-A/B-B 间隔；设置类命令不计入直接执行延迟。底层间隔图的横坐标是前一条命令的执行时间。

第二轮检查以下情况（精确规则见[事件规则](events/README.md)）：

| 类型 | 关注的问题 |
|---|---|
| `AMBUSH`（A） | 持续受到偷袭后，是否下达防守、撤离或反击命令；`kind=BASE` 是偷家 |
| `GRINDER`（B） | 我方刚在某个位置死过人，Agent 没重新获取战报就把部队派去集合或把建造者派去施工，结果在那里挨打 |
| `STALE_BURST`（C） | 短时间内连续被拒/失效的命令突发；单条不扣分，同一批发的命令有宽限 |
| `BUILDER_IDLE`（D） | 建造者完全空闲的时段、结束原因与当时能否建造 |
| `FACTORY_IDLE`（E） | 工厂完全空闲的时段；有 `keep-set` 补产规则的工厂不判为疏忽 |
| `economy.log` / `economy_seconds.csv`（F） | 每秒资金与收入，为空窗和决策提供背景；只出材料，不评分 |

所有时间均为游戏时间。底层命令执行不等于生产或移动完成；自动反击和自动化后续行为不算 Agent 新的主动响应，战损也不直接说明反应迟钝。

事件里带 `agentState`（`SILENT`/`OBSERVING`/`BUSY_ELSEWHERE`），用来区分"模型或链路停摆"和"在忙别的、漏了这个资产"；它只解释判定，不改变判定。

旧的 3–10 秒空窗判定（`BUILDER_GAP`/`PRODUCTION_GAP`）、`FEED`、`STALE_COMMAND`、`COMMAND_FAILURE` 已被上述六类取代，默认不运行；复现旧报告时用 `--config '{"legacyDetectors":true}'`。

## 保存评估输入

对局结束后保留这些文件：

| 文件 | 默认位置 | 含义 |
|---|---|---|
| `*.replay` | `replays/` | 原生录像，用于提取底层命令，也可重新重放补采证据 |
| `commands-*.txt` | `evaluate/output/` | 端口收到的高层请求，含游戏毫秒、玩家、参数及被拒绝的请求；不再另生成stats |
| `telemetry.jsonl` | `evaluate/output/events-live/match-NNN-yyyyMMdd-HHmmss-teamN/` | 单位任务、队列、伤害、死亡、经济及命令关联，是第二轮主要输入 |
| `economy.log` | 与telemetry同目录 | 每游戏秒的资金和HUD收入，每行60项，末行不补齐 |

两轮产物按对局归档。**对局名**取 commands 文件名去掉 `commands-` 前缀的部分（`commands-Ice-Island-(2p)-20261029--20-40.txt` → `Ice-Island-(2p)-20261029--20-40`），两轮默认写入 `evaluate/output/<对局名>/stage_1/`、`stage_2/`；Replay 解析结果写入同目录的 `replay/`，补采证据写入 `engine/`。文件名缺少该前缀时无法推断对局名，工具会直接报错并提示；把日志改名为 `commands-<对局名>.txt` 再运行，或用 `--out` 明确指定目录。

2026-10-30 之前生成的旧报告沿用 `evaluate/output/commands-…_analysis/`、`evaluate/output/[local]_…/` 布局，只作为历史结果保留，新流程不再写入这些目录。

同一进程连续多局可能共用一份commands文件，用 `match` 区分，评估前需拆成单局。按地图、玩家和游戏时间配对，不要只按文件序号选择。正常结束录像和采集，避免留下未闭合的数据。

## 运行第一轮评估

以下命令中，尖括号内的路径替换成自己的文件路径；均从仓库根目录运行。需要 Node.js 和 Python 3.11+。

### 1. 提取 Replay 底层命令

```sh
python evaluate/utils/replay.py "<回放文件.replay路径>" --commands "<commands.txt路径>"
```

目的：将录像解析为可读命令和结构化记录。

输出位于 `evaluate/output/<对局名>/replay/`：`.summary.txt` 是录像概要，`.txt` 是可读底层命令，只是方便人类读取的，`.jsonl` 是其的jsonl版本，作为下一步的输入。省略 `--commands` 时按录像文件名建目录（`evaluate/output/<回放安全文件名>/replay/`），与对局目录不同名。解析器会打印实际输出路径。

### 2. 分析命令节奏与执行匹配

```sh
node evaluate/evaluate_stage_1.js "<commands.txt路径>" "<回放命令.jsonl路径>"
```

目的：关联同局高层请求和底层执行，生成节奏、延迟、间隔图及离群明细。日志末次时间须超过300游戏秒，且A+B命令超过50条。

输出默认位于 `evaluate/output/<对局名>/stage_1/`：

| 文件 | 用途 |
|---|---|
| `report.html` | 主要阅读入口，包含图表与文字报告 |
| `report.txt`、`summary.json` | 命令节奏、间隔与离群汇总 |
| `classified_commands.csv`、`decision_cycles.csv`、`waiting_intervals.csv`、`command_intervals.csv` | 高层分类、决策周期、等待与间隔明细 |
| `native_command_intervals.csv` | 我方相邻底层命令间隔及原始记录行号 |
| `execution_report.txt`、`execution_summary.json` | 高低层匹配和执行延迟汇总 |
| `execution_matches.csv`、`execution_matches.jsonl` | 每条请求的匹配结果与候选证据 |
| `mapping_rules.json` | 本次使用的匹配规则 |

## 运行第二轮评估

下面两种方式任选一种。已有完整实时证据时，优先直接分析。

### 方式一：直接分析实时证据

```sh
node evaluate/evaluate_stage_2.js "<telemetry.jsonl路径>" --commands "<commands.txt路径>" --economy "<economy.log路径>" [--replay "<回放命令.jsonl路径>"]
```

目的：从当局采集的状态和命令关联中识别事件，并给出事件窗口内的资金/收入变化与相关主动命令。不重新运行游戏。

`--commands` 与 `--economy` **必须同时给出**，缺任一个直接报错退出，不产出报告：第二轮的事件判定依赖原始高层命令日志和逐秒经济序列，缺了它们无法区分响应来源和空窗原因。`--replay` 可选，用于把第一轮唯一归属的底层执行补进响应证据；不传时只依据命令日志的收包时间。

输出默认位于 `evaluate/output/<对局名>/stage_2/`，`--out "<目录>"` 可覆盖。

### 方式二：用 Replay 重新采集并分析

实时证据缺失或不完整时使用。需要 Java25 和同版本Headless发行包；尚未构建时执行：

```sh
.\gradlew.bat :desktop:headlessArchive
```

然后运行：

```sh
python evaluate/utils/evaluate_replay.py "<回放文件.replay路径>" --team "<玩家槽号>" --commands "<commands.txt路径>"
```

目的：真实重放录像，补采单位、战斗和经济证据，然后自动生成第二轮报告。Replay须在本仓库 `replays/` 内，玩家槽号与commands中的 `player` 一致。

`--commands` 必填，脚本会连同补采出的 `economy.log` 一起交给第二轮入口。省略 `--jsonl` 时先把录像解析到 `replay/`，也可传入已有解析结果避免重复解析。默认输出到 `evaluate/output/<对局名>/`，用 `--out "<目录>"` 指定其它目录。默认读取 `desktop/build/headless`，Java取 `JAVA_HOME` 或PATH；需要时用 `--dist "<发行包目录>"`、`--java "<Java程序路径>"` 指定。

两种方式都在 `stage_2/` 生成：

| 文件 | 用途 |
|---|---|
| `events.txt` | 可读事件时间线，建议先看 |
| `events.jsonl` | 事件依据、关联命令、时间和排除理由 |
| `summary.json` | 事件数量、状态、严格空窗、响应延迟、拒绝请求统计、过滤诊断及采样完整性 |
| `economy.log` | 从证据重建的经济序列 |

方式一只写这四个文件，输入证据保持原样。方式二另外把输入证据、`playback.log` 和 `playback.json`（输入哈希/校验结果）放在 `engine/`，把自动解析的录像命令放在 `replay/`。

## 如何读结果

第一轮中，`matched` 为匹配，`partial` 为部分覆盖，`unmatched` 为未找到对应记录，`ambiguous` 为归属不确定，`unobservable` 为缺少验证字段。未匹配不一定是执行失败，也可能是规则或证据不足。

第二轮中，`EXPLAINED` 表示候选有解释，`UNCERTAIN` 表示证据不足，`UNANSWERED` 表示未找到相关主动响应。结合 `strict` 和 `reason` 阅读，不把所有候选当作失误。

核对报告的 `complete/economyComplete`；重放补采还应检查 `playback.json` 中 `unchanged=true`、`checksumWarnings=0`。原始Replay、commands和有效telemetry应与报告一并保留。

### 初步评分与后处理（可选）

事件报告本身不含总分。需要一份可追溯的初评时，对 `stage_2/` 再跑一次后处理：

```sh
node evaluate/score_stage_2.js "evaluate/output/<对局名>/stage_2"
```

它在同一目录写出 `score.json`（逐条扣分依据）、`score.txt`（人类可读表格）、`timeline.csv`（按开始时间排序的全部事件）和 `economy_seconds.csv`（F 材料：每游戏秒的资金、HUD 收入字段与实测变化）。`--weights <json>` 覆盖默认权重，`--out <目录>` 换输出目录。

口径：加法模型，每个扣分点都能追溯到一条事件，每类有上限，避免单一失败模式主导总分；`EXPLAINED`/`UNCERTAIN` 和 `scoreable:false` 的事件不扣分；资金空余只出材料、不计分。权重与上限的默认值见 `events/score.js`，这是初版，不替代人工复核。

`score.txt` 另有一块**未计分时长**：证据没覆盖到（`UNCERTAIN`、`not_scoreable`）的空闲段不扣分，但按类汇总条数和秒数报出来。看总分前先看这一块——一局被中途掐断时，末段最长的空闲往往全落在未计分里。

注意 `economy.log` 里的收入字段单位是"每 `resourceIncomeRatePeriod`（40 tick）"，不是每秒；换算与实测差异见 `economy_seconds.csv` 的 `fundsDelta` 列。

### 命令拒绝率（`summary.json` 的 `rejections`）

`stage_2/summary.json` 里有一块独立的命令质量统计：

```json
"rejections": {"issued": 153, "rejected": 13, "rate": 0.085, "repeated": 2,
               "byCode": {"INTERNAL": 7, "UNAVAILABLE_ACTION": 3, "INSUFFICIENT_CREDITS": 2, "STALE_OBJECT": 1}}
```

- `rate` = 被拒 / 有回执的命令。它和 C 类（`STALE_BURST`，10 秒内 ≥3 批）互补：C 类只抓突发，一局里散落的十几次被拒会全部漏掉（实测某局 13 次被拒、C 类只出 1 条事件）。
- `repeated` = 30 秒内同一个原因码再次出现，说明 Agent 没有从回执里改过来。这衡量的是"错误恢复"，不是"反应速度"。
- 目前只报数，**不参与计分**：权重还没标定，等几局样本再决定。

### 会话复盘（`evaluate/session-cast/`）

Agent 的思考链是评测的重要旁证。把 Pi 的会话文件归档并生成基本分析：

```powershell
node evaluate/tools/session_cast.js C:\Users\<你>\.pi\agent\sessions\<目录>\<会话>.jsonl `
     --name "<对局名>" --match "<对局名>"
```

产出 `evaluate/session-cast/<对局名>/{session.jsonl, analysis.md}`：工具调用次数、被拒命令及引擎回执、思考量、单轮间隔、WSL 告警条数。整个目录被 `.gitignore` 忽略——会话是模型自己的推理记录，本地留存、不随仓库分发。

## 清理测试产物

微操每跑一次就写一个 `evaluate/output/micro/<CASE>/<run>/`，同一配置反复重跑会留下大量只差时间戳的兄弟目录；根目录 `replays/` 还会为每个 run 留一份 `<run>.replay`。整理时用：

```powershell
python evaluate/tools/cleanup_micro_output.py           # 只打印计划，不删
python evaluate/tools/cleanup_micro_output.py --apply   # 执行
```

口径：按 `(case, task, track, seed)` 配置分组，每组只保留一个 run（优先有 `result.json` + `eval/report.txt` 的，其次最新的有 `result.json` 的，**没有 `result.json` 的中断局一律不留**）；`replays/<run>.replay` 跟随其 run 存亡；`replays/[local] *.replay` 是真人局，永不动。

详细说明：[事件规则](events/README.md) · [采集与解析工具](utils/README.md) · [测试用途](test/README.md)
