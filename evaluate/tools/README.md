# 辅助工具

这四个脚本不属于两轮评估流程本身：它们整理评测产物、批量复算、核对引擎口径或复盘 Agent 会话，按需手动运行。两轮评估的正式入口见[总 README](../README.md)。

## `stage1_audit.js` — 批量复算全部 Stage1 报告

口径或评分规则改动后，需要确认"现有报告复算出来的数字有没有变"。`evaluate_stage_1.js` 一次只处理一局，这个脚本按 `execution_summary.json` 遍历 `evaluate/output/`，单局失败不中断其余样本：

```powershell
node evaluate/tools/stage1_audit.js                    # 就地重生成（覆盖现有报告）
node evaluate/tools/stage1_audit.js --out <目录>        # 写到别处，不动现有报告
node evaluate/tools/stage1_audit.js --resolve <目录>    # 输入缺失时额外搜索该目录（可重复）
```

每局输出一行 JSON（时长、A/B、分数、健康状态、开局里程碑、周期/等待、空窗占比、无落地原始/调整），最后汇总成功/跳过/失败数。缺原始输入（commands 或解析后的 JSONL）的样本记为跳过，退出码不受影响；只有评估本身报错才返回非零。

注意：早期扁平布局（`evaluate/output/commands-…_analysis/`，没有 `stage_1/`）的样本会被写到一个**新的** `<对局名>/stage_1/`——产物目录由 commands 文件名推出，不会覆盖旧目录。这类样本要人工比对后再替换。

## `cleanup_micro_output.py` — 清理微操运行产物

微操每跑一次就写一个 `evaluate/output/micro/<CASE>/<case>_<task>_<track>_s<seed>_<stamp>/`，同一配置反复重跑会留下大量只差时间戳的兄弟目录；根目录 `replays/` 还会为每个 run 留一份 `<run>.replay`。

```powershell
python evaluate/tools/cleanup_micro_output.py           # 只打印计划，不删
python evaluate/tools/cleanup_micro_output.py --apply   # 执行
```

口径：按 `(case, task, track, seed)` 配置分组，每组只保留一个 run（优先有 `result.json` + `eval/report.txt` 的，其次最新的有 `result.json` 的，**没有 `result.json` 的中断局一律不留**）；`replays/<run>.replay` 跟随其 run 存亡；`replays/[local] *.replay` 是真人局，永不动。

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
