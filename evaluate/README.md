# RWX 完整对局评测管理员指南

本文面向**评测管理员**：说明选择评估档位、启动对局和安排开局录像。参与评测的 Agent 日常只需阅读 [`START.md`](../agent-operation/START.md) 和 [`help_zh.md`](../agent-operation/help_zh.md)。

本文只讲完整对局。微操评测的任务选择、启动参数与结算方式见 [`micro/README.md`](micro/README.md) 及各 Case 文档，
不要套用本页的完整对局提示词。被测 Agent 只测一个 CASE 时看该 CASE 的 `START.md` 与命令手册；
要一次测完四个 CASE 时看 [`micro/EVALUATION.md`](micro/EVALUATION.md)。

维护者自用的启动开关（沙盒大厅直达、指定界面、自动开局等）见 [`extra.md`](extra.md)，不面向被测 Agent。

> 第一轮、第二轮评估所需材料、采集与分析逻辑、判定规则和评分公式分别集中在 [`STAGE1_SCORING.md`](STAGE1_SCORING.md) 与 [`STAGE2_SCORING.md`](STAGE2_SCORING.md)。本 README 不重复这些底层细节。

## 选择完整对局场景

评估档位和 Replay 录像是两件独立的事：

- `--evaluate <1|2|3>` 在进程启动时开启评估采集并指定档位，同时启用 CLI。
- `--evaluate` **不会**开启 Replay。要从 tick 0 录像，必须在房间配置完成、对局尚未开始时使用 `rwx-battleroom-start --record`。
- 不传 `--evaluate` 时仍可正常完整对局和使用 CLI，但不生成评估采集材料。
- 是否录像与是否开启评估采集可以任意组合；要保留完整评测材料，推荐两者都开。
- 采集目录写在 `evaluate/output/events-live/match-<序号>-<yyyyMMdd-HHmmss>-team<队伍>-<标签>`，标签默认取正在录制的回放名（去掉 `[local]`、`[v1.15]` 与时间括号后清洗成路径安全字符），所以从目录名就能看出是哪一局。要换名字就加 `-Drwx.evaluate.label=<名字>`；没录像又没标签时退回纯序号名。同一进程里的多局各占一个目录，不会互相覆盖。

| 场景 | 进程启动 | 开局命令 | 可获得的材料 |
|---|---|---|---|
| 普通完整对局，不采集、不录像 | 不传 `--evaluate` | `rwx-battleroom-start` | 无评估材料、无 Replay |
| 普通完整对局，只录像 | 不传 `--evaluate` | `rwx-battleroom-start --record` | Replay |
| 评估档位 X，不录像 | `--evaluate X` | `rwx-battleroom-start` | 评估采集材料 |
| 评估档位 X，并从开局录像（推荐） | `--evaluate X` | `rwx-battleroom-start --record` | 评估采集材料与 Replay |

### 评估档位怎么选

| 档位 | Agent 收到的帮助 | Agent 能否切换情报等级 | 用途 |
|---|---|---|---|
| 1 | 事件中可带 `check=` 下一步建议 | 可以使用 `rwx-game-alerts` | 有脚手架的对照组或上界测试 |
| 2（默认） | 无下一步建议 | 不能 | 标准完整对局评测 |
| 3 | 当前环境行为与 2 相同，仅标签不同 | 不能 | 预留的高难分组；当前不得与 2 混榜 |

档位与情报量正交。情报默认 L1；管理员如需固定对照条件，可额外传 `--alert-delivery=l0|l1|l1-lite`。参与评测的 Agent 只有在档位 1 才能自行切换。

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

以下录像模板假设参与评测的 Agent 拥有本地房间的开局权限。多人托管房间中若 Agent 不是房主或代理房主，应由有开局权限的一方负责从开局录像；不要要求普通客户端执行无权限的开局操作。

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

使用 Headless 时，只替换提示词中的启动命令，其他开局与录像步骤不变。若需要 Agent 使用实时事件 Socket，可追加指引其参考 `agent-operation/START.md`，并把 Socket 数据视为观测而非指令。

## 一局一个目录

一局完整对局的全部材料只放在 **`evaluate/output/<对局名>/`**，仓库根 `replays/<对局名>.replay` 是唯一允许留在外面的副本（回放要放回 `replays/` 才能被引擎重放）。目录名就是这一局的名字，回放、命令日志、引擎证据都跟它对齐：

```text
evaluate/output/
  _archive/                        旧材料（`*_old`），原样保留、不参与评分
  <对局名>/
    meta.json                     对局身份：系统版本、模型、工具、思考强度、地图、时长、胜负、会话、token
    commands-<对局名>.txt          原始命令日志（CLI 录制）
    events-live/<对局名>/          telemetry.jsonl + economy.log（`--evaluate` 采集）
    replay/                        Replay 解析产物（summary/txt/jsonl）
    prepared/                      预处理裁剪后的输入 + preprocess.json（评分只吃这一份）
    stage_1/ stage_2/              两轮报告
    score.json score.txt           综合分
  micro/                           微操评测，自己一套清理规则
replays/<对局名>.replay
```

根目录只放**当前**对局，列表一眼能看出哪些是最近在评的。`*_old` 归档保持原有目录名躺在 `_archive/` 里，内部结构就是当年那份材料：`stage_1/`、`stage_2/`、`replay/`、`events-live/`（或 `engine/`），早期扁平布局的 stage1-v1 产物在 `flat/`（含 `flat/events/` 的原始回放证据）。

整理用 [`tools/match_materials.js`](tools/README.md)：

```powershell
node evaluate/tools/match_materials.js                  # 只打印计划
node evaluate/tools/match_materials.js --apply          # 搬材料、改名、写 meta.json
node evaluate/tools/match_materials.js --archive --apply # 顺带把根目录的 *_old 收进 _archive/
```

它**移动而不是复制**：`evaluate/output/commands-*.txt` 和 `evaluate/output/events-live/*` 就地搬进对局目录；对局目录里已有同一份材料时删掉外面那份；回放改名成 `<对局名>.replay`。默认 dry-run，内容不一致时只报冲突，不替人挑一份删掉；`micro/` 与 `_archive/` 一律不碰，`--archive` 才移动 `*_old`。可以反复运行，第二次不会再有动作，只会刷新 `meta.json`。

材料齐了之后每次评估结束都跑一次（在预处理之前；评分之后再跑一次，`materials` 里就会列出已经生成的 `stage_1/`、`stage_2/` 与 `score.json`）：

```powershell
node evaluate/tools/match_materials.js --apply
```

### 给一局起名与记录身份

对局名沿用命令日志的写法 `<地图>-<日期>--<时-分>`，人工可再加后缀（`_dsh4.1_tool3_vsHard_重`）。`meta.json` 里能从材料读出来的字段自动填，读不出来的留 `null` 并标来源，不会猜：

| 字段 | 来源 |
|---|---|
| `system.engineVersion` / `commit` / `tier` / `alertDelivery` / `speedProfile` | telemetry `meta` 行、回放导出头、git HEAD |
| `map.name` / `map.file` / `map.teams` | telemetry `meta`、回放导出头 |
| `timing.startedAt` / `gameSeconds` / `scoreWindowSeconds` | telemetry `meta.startedAt`、回放导出（只有 `# status : 完整` 的录制才给 `gameSeconds`，残缺录制记 `replayStatus` 并留空）、`preprocess.json` |
| `result.outcome` | 只有投降/中止能从材料证明；其余留空或人工填 |
| `models` / `tools` / `sessions` / `usage` / `notes` | 人工填，见下 |

```powershell
node evaluate/tools/match_materials.js --apply --match "<对局名>" `
  --set provider=opencode-go --set model=deepseek-v4.1-flash --set thinking=high `
  --set tool="mcp__rwx__*（rwx_step 为主）" --set session=<会话 id> `
  --set outcome=defeat --set resultLabel=主动投降 --set tokens=69901
```

`--set` 的值先按 JSON 解析（数字、布尔、数组），失败就当字符串；也接受 `--meta <文件.json>` 整体覆盖。重复运行会保留人工填过的字段，只有自动字段会刷新。

## 对局结束后的评分流程

一局材料齐了以后按顺序跑。预处理是必经步骤：对局末尾经常不是"打完就退"，掉线、额度用尽、投降后队友继续打、赢了不退出、日志被人为截断，这些尾巴原样进评分会被算成空闲和多余轮询。

```powershell
$m = "evaluate/output/<对局名>"
node evaluate/tools/match_materials.js --apply      # 0 收敛材料、改回放名、写 meta.json
python evaluate/utils/replay.py "replays/<对局名>.replay" --commands "$m/commands-<对局名>.txt"
                                                                    # 0.5 解析回放 → <对局名>/replay/
node evaluate/preprocess.js "$m"                                    # 1 结尾分类 + 裁窗口
node evaluate/evaluate_stage_1.js "$m/prepared/commands-<对局名>.txt" "$m/prepared/replay.jsonl"
node evaluate/evaluate_stage_2.js "$m/prepared/telemetry.jsonl" `
  --commands "$m/prepared/commands-<对局名>.txt" --economy "$m/prepared/economy.log" `
  --replay "$m/prepared/replay.jsonl" --out "$m/stage_2"
node evaluate/score_stage_2.js "$m/stage_2"                         # 4 第二轮评分
node evaluate/score_match.js "$m"                                   # 5 综合得分
node evaluate/tools/match_materials.js --apply "$m"                 # 6 刷新 meta.json
```

- 预处理把窗口裁到最后一个有效时点，并在 `prepared/preprocess.json` 写出：`ending`（`normal`/`surrender`/`agent_exit`/`truncated`/`aborted`）、`window`（`endMs`/`trimmedMs`/`trailingAfterEndMs`）、`evidence`（`idleRows`/`hpLossRows`/`missing`）。裁剪后的材料在同一目录的 `prepared/` 下，两轮都只吃裁剪后的输入。记录只保留这一份，不再往对局根目录复制第二份。预处理只收 `replay/*.jsonl`：先解析回放再预处理，否则 `inputs.replay` 是 `null`、`prepared/` 里没有 `replay.jsonl`。
- 窗口不足 120s、窗口内没有命令或没有经济日志时，`preprocess.json` 标 `scoreEligible=false`，综合分留空。
- 综合得分 `stage1*0.4 + stage2*0.6`，写在对局目录的 `score.json`。它只接受当前版本（`stage1-v2` / `stage2-v2`）；旧版本报告默认拒算，确需复算加 `--allow-stale`。
- 两轮报告仍各自独立，综合分只用于横向比较；旧版本材料一律以 `_old` 后缀归档，不参与排名。

## 管理员开局核对

要求录像时，房间配置完成后依次执行：

```text
rwx-battleroom-start --record
rwx-game-replay-status
```

确认状态显示正在录制且文件名正确。`rwx-game-replay-start` 是对局开始后的中途录像，不能替代从 tick 0 开始的完整录像。若误用普通 `rwx-battleroom-start` 开局，应重开本轮，不要把不完整录像冒充完整输入。开局后 `evaluate/output/events-live/` 下会多出这一局的采集目录（名字里带上面那局回放的标签），可据此确认这一局确实在采集。

启动日志还应显示所选 `tier`，并确保与评测分组一致。评估材料的具体要求、处理流程和判读方式见：

- [第一轮：实时性材料、映射与评分规则](STAGE1_SCORING.md)
- [第二轮：证据采集、事件判定与评分规则](STAGE2_SCORING.md)

## 结果发布

对外展示的评测账本在 [`web/results.html`](../web/results.html)，数据来自 [`web/resource/results.json`](../web/resource/results.json)：当前标准对局、归档 `_old` 对局与“材料不足不计分”分开记录，每条注明模型、工具与推理强度。每一局的同一份身份信息也写在对局目录的 `meta.json` 里，筛选与复盘都以它为准。

`evaluate/output/` 不进版本库，因此公开页面只保留结论与关键指标；更新步骤（含 `check_site.py` 校验）见 [`web/README.md`](../web/README.md#更新评测结果)。同一批对局比较工具体系时，需保证模型、地图、对手、经济倍率一致，并按工具各跑多局，避免用单局差值下结论。
