# RWX 完整对局评测管理员指南

本文面向**评测管理员**：说明选择评估档位、启动对局和安排开局录像。参与评测的 Agent 日常只需阅读 [`START.md`](../agent-operation/START.md) 和 [`help_zh.md`](../agent-operation/help_zh.md)。

本文只讲完整对局。微操评测的任务选择、启动参数与结算方式见 [`micro/README.md`](micro/README.md) 及各 Case 文档，不要套用本页的完整对局提示词。

> 第一轮、第二轮评估所需材料、采集与分析逻辑、判定规则和评分公式分别集中在 [`STAGE1_SCORING.md`](STAGE1_SCORING.md) 与 [`STAGE2_SCORING.md`](STAGE2_SCORING.md)。本 README 不重复这些底层细节。

## 选择完整对局场景

评估档位和 Replay 录像是两件独立的事：

- `--evaluate <1|2|3>` 在进程启动时开启评估采集并指定档位，同时启用 CLI。
- `--evaluate` **不会**开启 Replay。要从 tick 0 录像，必须在房间配置完成、对局尚未开始时使用 `rwx-battleroom-start --record`。
- 不传 `--evaluate` 时仍可正常完整对局和使用 CLI，但不生成评估采集材料。
- 是否录像与是否开启评估采集可以任意组合；要保留完整评测材料，推荐两者都开。

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

## 对局结束后的评分流程

一局材料齐了以后按顺序跑。预处理是必经步骤：对局末尾经常不是"打完就退"，掉线、额度用尽、投降后队友继续打、赢了不退出、日志被人为截断，这些尾巴原样进评分会被算成空闲和多余轮询。

```powershell
$m = "evaluate/output/<对局名>"
node evaluate/preprocess.js "$m"                                    # 1 结尾分类 + 裁窗口
node evaluate/evaluate_stage_1.js "$m/prepared/commands-<对局名>.txt" "$m/prepared/replay.jsonl"
node evaluate/evaluate_stage_2.js "$m/prepared/telemetry.jsonl" `
  --commands "$m/prepared/commands-<对局名>.txt" --economy "$m/prepared/economy.log" `
  --replay "$m/prepared/replay.jsonl" --out "$m/stage_2"
node evaluate/score_stage_2.js "$m/stage_2"                         # 4 第二轮评分
node evaluate/score_match.js "$m"                                   # 5 综合得分
```

- 预处理把窗口裁到最后一个有效时点，并在对局目录写出 `preprocess.json`：`ending`（`normal`/`surrender`/`agent_exit`/`truncated`/`aborted`）、`window`（`endMs`/`trimmedMs`/`trailingAfterEndMs`）、`evidence`（`idleRows`/`hpLossRows`/`missing`）。裁剪后的材料在同一目录的 `prepared/` 下，两轮都只吃裁剪后的输入。
- 窗口不足 120s、窗口内没有命令或没有经济日志时，`preprocess.json` 标 `scoreEligible=false`，综合分留空。
- 综合得分 `stage1*0.4 + stage2*0.6`，写在对局目录的 `score.json`。它只接受当前版本（`stage1-v2` / `stage2-v2`）；旧版本报告默认拒算，确需复算加 `--allow-stale`。
- 两轮报告仍各自独立，综合分只用于横向比较；旧版本材料一律以 `_old` 后缀归档，不参与排名。

## 管理员开局核对

要求录像时，房间配置完成后依次执行：

```text
rwx-battleroom-start --record
rwx-game-replay-status
```

确认状态显示正在录制且文件名正确。`rwx-game-replay-start` 是对局开始后的中途录像，不能替代从 tick 0 开始的完整录像。若误用普通 `rwx-battleroom-start` 开局，应重开本轮，不要把不完整录像冒充完整输入。

启动日志还应显示所选 `tier`，并确保与评测分组一致。评估材料的具体要求、处理流程和判读方式见：

- [第一轮：实时性材料、映射与评分规则](STAGE1_SCORING.md)
- [第二轮：证据采集、事件判定与评分规则](STAGE2_SCORING.md)
