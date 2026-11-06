# 辅助工具

这几个脚本不属于三阶段评估流程本身：它们整理评测产物、批量复算、核对引擎口径或复盘 Agent 会话，按需手动运行。正式入口见[总 README](../README.md)。

## `match_materials.js` — 一局只留一个目录，并记录 `meta.json`

一局打完后材料本来散在四处：`evaluate/output/commands-<名>.txt`、`evaluate/output/events-live/match-…/`、`evaluate/output/<名>/`、仓库根 `replays/<名>.replay`。评测做完经常两边都留一份，谁也说不清哪份是权威。这个脚本把它们收敛成 `<名>/` 一个目录：

```powershell
node evaluate/tools/match_materials.js                  # 只打印计划，不动文件
node evaluate/tools/match_materials.js --apply          # 真的搬/改名/删重复/写 meta
node evaluate/tools/match_materials.js --apply "<对局名>"  # 只处理一局
node evaluate/tools/match_materials.js --archive --apply  # 顺带把根目录的 *_old 收进 _archive/
```

规则：

- **移动而不是复制**。命令日志搬成 `<名>/commands-<名>.txt`；引擎证据搬成 `<名>/events-live/<名>/`；对局目录里已有同一份材料时删掉外面那份。回放是唯一例外：仍在 `replays/`，但改名成 `<名>.replay`，和目录同名。
- 只有回放、没有任何评估材料的局只报告、不建目录。否则会把 `_old` 归档里的旧局"复活"成新对局。
- `micro/` 与 `_archive/` 一律不碰；内容不一致时只报 `CONFLICT`，不替人挑一份删掉。
- `--archive` 把根目录里名字以 `_old` 结尾的条目（目录或单个文件）原样搬进 `evaluate/output/_archive/`，根目录只留当前对局。归档不配对、不改名、不写 `meta.json`。
- 可以反复运行：第二次只剩刷新 `meta.json`。

`meta.json` 记这一局的身份：系统版本（引擎版本 + git commit）、参与的模型与思考强度、工具情况、日期、地图配置、时长、胜负、关联会话、token 用量。能从材料读出来的自动填，读不出来留 `null` 并写来源；人工用 `--set key=value`（`model`/`provider`/`thinking`/`tool`/`session`/`tokens`/`outcome`/`note` 等短名字，或任意点号路径）或 `--meta <文件.json>` 补。

## `rebuild_telemetry.js` — 把回放播放证据补成 live 形状

`evaluate_replay.py` 默认会调用它。引擎重放只写"引擎实际接受了什么"；live telemetry 还有一半来自 CLI：每条请求的 `command` 收据、`result` 判决、native 行的 `source`/`commandId`。被拒请求不进 replay，所以这一半必须用同局命令日志补回来。

```powershell
node evaluate/tools/rebuild_telemetry.js --telemetry <播放telemetry> --commands <commands.txt> --replay <replay.jsonl> --out <merged.jsonl> [--started-at <iso>]
```

- 输入三份：播放 telemetry、命令收据日志、`replay.py` 解析出的 JSONL；输出一棵按游戏时钟排序、重排 `seq` 的 telemetry。
- 插入 `command`/`result` 行（refs 带收据时刻的死亡时间），把与收据对上的 replay 命令的 native 行标成 `source=agent`/`commandId`；AI 那侧标 `engine`，没有收据的自动化保持 `replay_unknown`。
- 判决分两档：命中 replay 的请求 `accepted=true`；收据时刻点名的对象**全部**已死/失踪的请求按引擎口径写 `STALE_OBJECT`（可证实）；其余未命中 replay 的操作请求写 `accepted=false` + `OTHER` + `inferred:true`（引擎没执行它，但收据日志没有回执，原因不可还原）；只有 JSONL 字段不足等真正未知的情况才留 `accepted=null`。
- 压缩的 `frames` 行先展开成逐帧再插入，避免把一段压缩运行拆开。
- 原始播放文件由 `evaluate_replay.py` 保留为 `telemetry.engine.jsonl`，合并结果写成 `telemetry.jsonl`，下游 `preprocess.js` / `evaluate_stage_2.js` / `evaluate_stage_3.js` 直接读取合并结果。
- 收据日志里晚于回放 `end` 的请求（对局结束后继续轮询、退出）会被丢掉并计入 `meta.rebuilt.droppedAfterEnd`：那时已经没有引擎状态可判定，`preprocess` 本来也会把这段尾巴裁掉；不丢会让游戏时钟倒走、第二阶段直接拒读。

## `stage3_baseline.js` — 求第三阶段 `S0` 并写标定后的报告

两项饱和公式（偷袭、建筑炮击）的 `S0` 只能用基线 `S` 的中位数。这个脚本扫描已经算好的 `<局>/stage_3/`，重算并汇总：

```powershell
node evaluate/tools/stage3_baseline.js                    # 读 evaluate/output/_archive，只打印
node evaluate/tools/stage3_baseline.js --write            # 顺带写各局 score.calibrated.json/.txt
node evaluate/tools/stage3_baseline.js --match "<甲>" --match "<乙>" --out agent-tmp/x.json
node evaluate/tools/stage3_baseline.js --s0 evaluate/output/_archive/_stage3-baseline.json `
  --match "evaluate/output/<甲>" ... --out evaluate/output/_stage3-standard.json --write
```

- 只统计 `summary.complete=true` 且 `resim/playback.json.checksumWarnings=0` 的局；证据不全、重放分叉的局单独列出并写明原因，不参与中位数。
- 某一族整批没有记录时保留 `config.json` 里的 `S0`（不重置成 1，也不除以 0），报告写“该族无记录，沿用 config.json 的 S0”。
- 聚合结果（各局 `S`、`N` 直方图、类型占比、危险区漏斗、标定后扣分与总分）写到 `--out` 的 JSON 和同名 `.md`，默认在 `_archive/` 下。
- 标定后的报告仍写 `provisional:true` + `s0Source:"archive-median"`：归档语料模型/提示词不统一，只能当工作标定。
- `--s0 <已有基线.json>` 冻结一组 S0（可直接传另一份 `_stage3-baseline.json`），给新一批局打分时不再重新标定；此时 `s0Source="fixed-baseline"`、`baseline.source` 记录来源，分数仍 `provisional`。
- `--kind <名字>` 只改聚合里的 `baseline.kind/source` 标签（如 `standard-corpus`），`--title` 改报告标题。

## `stage3_details.js` — 把第三阶段每一条记录的扣分明细摊开

`stage3_baseline.js` 回答“每局多少分”，这个脚本回答“这些分是哪几条记录产生的”：读各局 `stage_3/score.calibrated.json`（没有就退回 `score.json`）与 `events.jsonl`，逐条列出 `L`/`W`/`w`/`Tf`/`s`、事件时间、单位/建筑类型、`N`、`E`/`H`，并把族扣分按 `s` 比例摊到每条（“份额”）。

```powershell
node evaluate/tools/stage3_details.js                       # 写 evaluate/output/_archive/_stage3-detail.md
node evaluate/tools/stage3_details.js --note "说明"         # 报告头部追加一条说明
node evaluate/tools/stage3_details.js --match "<甲>" --out agent-tmp/x.md
```

- 份额只是饱和函数的比例归因，不能当可加分数：`扣分 = P_max·(1−e^(−S/S0))` 是非线性的。
- 绞肉机列出合并后的事件（区、首死亡、权重和、`age`、`B`、`L`）与每条被排除的建造者死亡原因。
- 重放分叉（`resim/playback.json.checksumWarnings>0`）的局照样列，但表头标「排除」并写明不一致条数。
- 没有 `stage_3` 的局直接跳过，不占位。

## `evaluate_corpus_v2.js` — 整批跑 v2 总分（含归档局）

一次扫完 `evaluate/output` 全树（含 `_archive/old_v1.0` 与 `_archive/old_v1.1`），逐局算 `stage1-score-v2` + `stage3-score-v2` + `total-v2`：

```powershell
node evaluate/tools/evaluate_corpus_v2.js                     # 全部可评估的局
node evaluate/tools/evaluate_corpus_v2.js --match "<对局目录>"  # 只跑一局
```

- **证据选择**：`prepared/telemetry.jsonl` 里有 `stage3_meta` 就直接用 live；否则要求 `resim/telemetry.jsonl` 存在且 `resim/playback.json.checksumWarnings === 0`，否则该局记入忽略（`replay_desync:<N>` / `commands_or_economy_missing` / `stage3_evidence_unavailable`），不猜分。
- 每局写 `evaluation_v2.json`（评分窗口、结局/战败投降、`evidenceSource`、两个阶段的分数、可选的旧 `stage1v1`/`stage2` 对照），并把握全部结论、忽略原因和 **>15min 调参池**的 `S` 中位数汇总到 `evaluate/output/_v2-summary.json`。
- 调参池只取 `config.json` 的 `policy.tuningPool.archives`（本轮为 `old_v1.1`）里、评分窗口严格大于 15 分钟的局；`old_v1.0` 照常评分但不进池。
- 每局的 `archive` 取自目录：`_archive/<标签>/…` 用该标签，其余算当前批次（`old_v1.1`），所以 `old_v1.0` / `old_v1.1` 不需要在代码里写死名单。
- 结局由 `preprocess.js` 的 `planWindow` 判定（投降/退出/我方指挥中心被毁/敌方全灭/正常结束，取最早），所以同一局在 v2 里的窗口与旧 `stage_2` 的窗口可能不同。
- 它只重算评分，不重跑 `preprocess.js`；窗口 ms 直接传给两个评分器（`--cut-ms`），所以 `prepared/` 与 `resim/` 必须已经是当前版本的产物。

## `build_web_results_v2.js` — 把 v2 汇总写进网页账本

`_v2-summary.json` 与 `evaluate/output/` 都不进版本库，页面读不到。这个脚本把结论写成 `web/resource/results.json`（schema 2）：

```powershell
node evaluate/tools/build_web_results_v2.js
python web/tools/check_site.py
```

- 每局一条：`title`（`meta.display_name` 优先）、`pairs`（模型 × 数量 × 工具，工具改造放 `extra`）、`duration`（评分窗口 + 游戏秒）、`result`（胜负/投降）、`scores`（`totalV2`/`stage1V2`/`stage3` 与可选的旧分）、`stage3.deductions`（四项扣分与上限）。
- 归档语料没有结构化 `meta.json` 的，用脚本顶部的 `ARCHIVE` 表补日期与参与模型；缺的字段写 `unknown`，不猜 provider/推理强度。
- 忽略的局单列进 `ignored`（原因 + 相对路径），页面上是"未进入排名"。
- `check_site.py` 会校验 `total-v2` 与两阶段加权一致、四项 Stage3 齐全、`pairs` 字段完整。

## `results_details.js` — 把逐项扣分证据写进网页账本（old_v1.1 旧账本）

评测页要对每一局展开「第一阶段 12 项 + 第二阶段 6 类」的扣分明细，但 `web/resource/results.json` 在版本库里、`evaluate/output` 不进版本库，页面自己读不到评分产物。这个脚本做桥梁：

> 2026-11-06 起评测页改用 `total-v2` 账本（schema 2），不再有 `stage1Items`；本脚本只用于复现 `old_v1.1` 之前的旧账本，新流程请用 `build_web_results_v2.js`。

```powershell
node evaluate/tools/results_details.js           # 把证据写进 results.json
node evaluate/tools/results_details.js --check   # 只比对，不写；有漂移时退出码 1
```

- 从 `stage_1/score.json` 的 `evidence` 生成第一阶段每项的「本局证据」（首个行动秒数、周期均值、离群数、底层间隔分布……）；从 `stage_2/score.json` 的逐事件明细生成第二阶段每类的说明（可计分/不可计分的闲置段数、偷袭加权冲击与最大一次、资金闲置时长与峰值……）。
- 第二阶段 6 类固定顺序输出；某一局没有该类事件时补 `raw/capped = 0` 与「本局未出现该类事件」，页面才能逐局对齐。
- 写回的是 `metrics.stage1Items[].detail` 与 `breakdown.stage2.families[]`（`raw/capped/cap/events/detail`）。分值以评分产物为准：账本分数与 `score.json` 对不上、或缺某一项时脚本直接报错不写，避免页面展示旧分。
- 新对局要在脚本的 `DIRS`（`id` → `evaluate/output/<对局名>`）里补一行，或直接在账本条目里写 `match` 字段；历史上有两局目录名与工具编号相反（`…07-22__dsh4.1_tool1_vsHard` 实际是 tool2），映射表已按会话与录像核对过。

## `stage1_audit.js` — 批量复算全部 Stage1 报告

口径或评分规则改动后，需要确认"现有报告复算出来的数字有没有变"。`evaluate_stage_1.js` 一次只处理一局，这个脚本按 `execution_summary.json` 遍历 `evaluate/output/`，单局失败不中断其余样本：

```powershell
node evaluate/tools/stage1_audit.js                    # 就地重生成（覆盖现有报告）
node evaluate/tools/stage1_audit.js --out <目录>        # 写到别处，不动现有报告
node evaluate/tools/stage1_audit.js --resolve <目录>    # 输入缺失时额外搜索该目录（可重复）
```

每局输出一行 JSON（时长、A/B、分数、健康状态、开局里程碑、周期/等待、空窗占比、无落地原始/调整），最后汇总成功/跳过/失败数。缺原始输入（commands 或解析后的 JSONL）的样本记为跳过，退出码不受影响；只有评估本身报错才返回非零。

命令日志被 `match_materials.js` 改名后，旧报告里的 `file1` 仍指向改名前的路径；脚本会退回同一对局目录里的 `commands-<对局名>.txt`，不需要先把关卡搬回去。

注意：早期扁平布局（`evaluate/output/commands-…_analysis/`，没有 `stage_1/`）的样本会被写到一个**新的** `<对局名>/stage_1/`——产物目录由 commands 文件名推出，不会覆盖旧目录。这类样本要人工比对后再替换。

## `cleanup_micro_output.py` — 清理微操运行产物

微操每跑一次就写一个 `evaluate/output/micro/<CASE>/<case>_<task>_<track>_s<seed>_<stamp>/`，同一配置反复重跑会留下大量只差时间戳的兄弟目录；根目录 `replays/` 还会为每个 run 留一份 `<run>.replay`。

```powershell
python evaluate/tools/cleanup_micro_output.py           # 只打印计划，不删
python evaluate/tools/cleanup_micro_output.py --apply   # 执行
```

口径：按 `(case, task, track, seed)` 配置分组，每组只保留一个 run（优先有 `result.json` + `eval/report.txt` 的，其次最新的有 `result.json` 的，**没有 `result.json` 的中断局一律不留**）；`replays/<run>.replay` 跟随其 run 存亡。

它只认微操 run 名（`CASE<n>-…_realtime|pause|human_s<seed>_<时间戳>`）。完整对局的回放现在叫 `<对局名>.replay`（与 `evaluate/output/<对局名>/` 同名），`[local] *.replay` 是真人局：这两类都不归它管，永远不会被删。

## `session_cast.js` — 归档并分析 Agent 会话

Agent 的思考链是评测的重要旁证。把 Pi 的会话文件归档并生成基本分析：

```powershell
node evaluate/tools/session_cast.js C:\Users\<你>\.pi\agent\sessions\<目录>\<会话>.jsonl `
     --name "<对局名>" --match "<对局名>"
```

产出 `evaluate/session-cast/<对局名>/{session.jsonl, analysis.md}`：工具调用次数、被拒命令及引擎回执、思考量、单轮间隔、WSL 告警条数。整个目录被 `.gitignore` 忽略——会话是模型自己的推理记录，本地留存、不随仓库分发。`--out <目录>` 换输出目录。

## `income_audit.js` — 核对收入口径

引擎按 `resourceIncomeUpdateInterval`（10 tick）结算、HUD/CLI 按 `resourceIncomeRatePeriod`（40 tick）报速率，两者只在常量与时钟步长一致时才等于实际到账。这个脚本挑出"没有下单的安静秒"，直接比对资金实增与两个速率字段，不信任任一侧的换算。

输入是一个按 `kind` 区分三类行的 JSONL：`command`（`timeMs`/`name`/`id`）、`economy`（`sampleMs`/`funds`/`income`/`incomePerSecond`）、`result`（`commandId`/`reply`，用来抓 `rwx-game-state` 回复里的 `Credits: N (+M/s)`）。

```powershell
node evaluate/tools/income_audit.js <evidence.jsonl>
```

输出 `fieldToDelta`、`ipsToDelta` 两个比值：接近 1 说明该字段就是每秒到账，接近 1.5625 说明它是"每 40 tick"。用于确认 `rwx-game-state` 的 `incomePerSecond` 可信，以及 `economy.log` 的 `income` 为什么不是每秒。
