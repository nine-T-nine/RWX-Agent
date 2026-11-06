# 第三阶段：延迟严重程度评估（`stage3-score-v2`）

本文件说明 [`score_design.md`](score_design.md#第三阶段) 第三阶段在代码里的落地口径。四项分别是：

| 族 | 含义 | 扣分上限 |
|---|---|---|
| `STAGE3_AMBUSH` | 空闲移动单位被打 | 50 |
| `STAGE3_BARRAGE` | 建筑被炮兵持续攻击 | 50 |
| `STAGE3_GRINDER` | 危险区里仍有建造者死亡（绞肉机 v2） | 20 |
| `STAGE3_FUNDS` | 资金长期空余（资金 v2，读 `economy.log`） | 30 |

四项独立扣分、各自封顶后相加；`总分 = max(0, 100 − Σ扣分)`。所有数值参数只在 [`config.json`](config.json) 的 `stage3.parameters` 里维护，代码通过 [`config.js`](config.js) 读取，**不要在脚本里另写默认值**。设计尚未确定或没有数据标定的项在 `config.json` 里带 `"status": "provisional"`。

第三阶段不再只是单列成绩：`total-v2 = stage3 × k + stage1-v2 × (1 − k)`（`k=0.6`）由 [`score_match_v2.js`](score_match_v2.js) 合成总分；仍是 provisional，理由写在分数的 `calibration.reasons` 里。

## 材料与采集

用当前版本 `--evaluate <档位>` 跑，或用当前 Headless 发行包重放 Replay。`telemetry.jsonl` 除原有行外需要：

| 行 | 内容 |
|---|---|
| `stage3_meta` | 炮兵攻击间隔、危险区半径与建区参数 |
| `stage3_ambush` | 空闲移动单位每次受击及缓冲关闭；建造者执行 build/repair 仍视为空闲，运输中不计 |
| `stage3_barrage` | 建筑遭自行火炮、重型火炮、火炮机甲命中的缓冲与关闭原因 |
| `stage3_zone` | 危险区 create/refresh/check/clear/drop 状态机及每次 E/F/ratio |
| `stage3_builder_death` | 四类建造者的完整漏斗：伤害击杀、运输、覆盖区、age、是否可计分 |

伤害通知同时记录实际 `hpLoss` 与 `shieldLoss`，均在伤害结算前后取真值，过量伤害不计。HP/盾上限和累计造价在缓冲创建时冻结。炮兵 `K` 从游戏单位数据的最大攻击间隔读取，默认原版值为 4 秒，`K_tol = 1.3K = 5.2 秒`。

资金项不吃 `telemetry.jsonl`，它读 `economy.log`（每秒一行，`funds` 与收入字段；`incomeFieldPerSecond=1.5625` 把 0.64 秒一格的收入字段换算成每秒资金）。`score_stage_3.js` 默认在 `prepared/`、`resim/` 或 `stage_3/` 里找它，也可以用 `--economy` 指定。

危险区严格按设计顺序在同帧批处理：死亡台账 → 刷新已有区 → 4 秒节拍检查 → 按 id 聚类建区 → 判定建造者死亡。区域造价使用游戏真值，不受视野限制；所有阈值与每次判定的 E/F 都写进原始材料。

## 扣分公式

公共项：`E = D_hp + α·D_sh`，`H = HP_max + α·Shield_max`，`L = 1`（致死/摧毁），否则 `β·min(1, E/H)`；`W = W_min + (1 − W_min)·min(1, ln N / ln N_sat)`；`w = clip(sqrt(cost/C_w), w_lo, w_hi)`；`S = Σs / clip(G/G_ref, r_lo, r_hi)`；`扣分 = P_max·(1 − e^{−S/S0})`。

- **偷袭**：`s = w·L·W·Tf`，`Tf = 1 + γ·min(1, max(0, (T − T0)/T_span))`。`T` 是缓冲从首次 idle 受击到关闭的时长，`T0` 以下不加成。
- **炮击**：`s = w·L·W`，没有时长项（炮兵间隔已经体现在 `N` 与缓冲区里）。
- **绞肉机**：同一有效区内以第一次死亡为锚点，`T_merge` 内的死亡合并成一个事件；`B = min(1, Σw / W_full)`，`age = t_first − t_birth − Δ0`，`L = L_min + (1 − L_min)·min(1, age / T_stale)`，`P = SingleMax·L·B`，`扣分 = min(Cap, ΣP)`。不做时长归一。
- **资金**：先对每秒资金做 3 秒中值平滑；阈值 `θ = K × 近 30 秒平均收入率`。资金首次达到 `θ` 开始计时，累计囤积时长 `D` 在区间内取最大值 `d_k`。资金回落到 `θ` 以下、或一次释放超过 `releaseShare`（默认 80%）时区间结束。花费按 `s > max(spendFloor, spendShare·M)` 才计入（`M` 为区间内资金最大值），并按 `s/M` 折减 `D`：≤50% 乘 `rho1`，50%~80% 乘 `rho2`。区间结算 `f(d) = (e^{λu} − 1)/(e^{λ} − 1)`，`u = (d − d0)/(d1 − d0)`，`d ≤ d0` 为 0、`d ≥ d1` 为 1；`扣分 = min(Cap, Σ eventMax·f(d_k))`。设计里的动态标定（`K`、`d0/d1`、`κ`、向先验收缩）还没有参考池，`config.json` 把它们标成 provisional 或 `disabled`。

## 离线流程

```powershell
$m = "evaluate/output/<对局名>"
node evaluate/evaluate_stage_3.js "$m/prepared/telemetry.jsonl" --out "$m/stage_3"
node evaluate/score_stage_3.js "$m/stage_3" --cut-ms <评分窗口 ms>
```

第一条命令只做材料门槛、缓冲重建和漏斗整理；第二条命令才计分。`--cut-ms` 与预处理窗口一致，避免把窗口外的资金区间算进来。输出：

| 文件 | 用途 |
|---|---|
| `stage_3/events.jsonl` | 三类规范事件及排除原因 |
| `stage_3/zones.jsonl` | 危险区完整状态机，供人工复核和重新标定 |
| `stage_3/summary.json` | 覆盖、漏斗、区寿命和输入身份 |
| `stage_3/events.txt` | 简明事件时间线 |
| `stage_3/score.json` | 四项参数、逐项中间量、诊断和总分 |
| `stage_3/score.txt` | 人读摘要 |
| `stage_3/timeline.csv` | 可排序的事件表（含资金区间） |

旧 telemetry 没有 `stage3_meta` 时入口会拒绝，不能把"没有采集"解释成"零事件"；窗口截断且没有关闭行的缓冲保留为 `INCOMPLETE_INTERVAL`，不扣分。没有 `economy.log` 时资金项记 `ECONOMY_EVIDENCE_MISSING`、`evidenceComplete=false`，这时不会出 `score_v2.json`。

## 归档局的加速重放（resim）

`evaluate/output/_archive/<old_v1.0|old_v1.1>/` 里录制时还没采集第三阶段证据的局，只能重放补：

```powershell
$m = "evaluate/output/_archive/old_v1.1/<对局名>"
python evaluate/utils/evaluate_replay.py "replays/<对局名>.replay" --team <队伍> `
  --commands "$m/commands-<对局名>.txt" --out "agent-tmp/<slug>"
Copy-Item "agent-tmp/<slug>/engine/match-*/telemetry.jsonl" "$m/resim/telemetry.jsonl"
Copy-Item "agent-tmp/<slug>/engine/match-*/economy.log"     "$m/resim/economy.log"
```

`resim/playback.json` 记录回放名与 SHA-256、队伍、`checksumWarnings`、引擎提交和耗时，是这份证据的来源凭证。规则：

- 重放窗口止于 `replay_end`（回放里没有 `match_result`），不做 `preprocess` 的尾部裁剪；窗口里的结局由 `evaluate_corpus_v2.js` 用 `planWindow` 重新判定。
- `checksumWarnings > 0` 表示重放与录制分叉，这份材料不进入 v2 总分（`replay_desync:<N>`）。当前语料里 4 局是这样，另有 1 局因回放未完成、材料缺失被忽略。
- 录制完整、重放零分叉的局才写 `evaluation_v2.json`，并标注 `evidenceSource=faithful-resim`。

## S0 标定

两项饱和公式的 `S0` 只能用基线 `S` 的中位数：

```powershell
node evaluate/tools/stage3_baseline.js                      # 默认扫 evaluate/output/_archive
node evaluate/tools/stage3_baseline.js --write --out evaluate/output/_archive/_stage3-baseline.json
```

- 只用 `summary.complete=true` 且 `resim/playback.json.checksumWarnings=0` 的局求中位数；证据不全或分叉的局单独列出，不参与。
- 某一族整批 0 条记录时保留 `config.json` 里的 `S0`（不重置成 1，也不除以 0），报告会注明"该族无记录，沿用 config.json 的 S0"。
- `--write` 写 `<局>/stage_3/score.calibrated.json` 与 `.txt`；聚合写到 `--out` 的 JSON 和同名 `.md`。
- 给新一批局打分而不再重新标定时，把已有聚合冻结进去：`node evaluate/tools/stage3_baseline.js --s0 <已有聚合.json> --out <新的聚合> --write`，此时记 `s0Source:"fixed-baseline"`。
- 本轮 `S0` 只用 **`old_v1.1`（2026-11-06 退役的那一批）里评分窗口 >15min 的 4 局**（Fire Bridge、MCPtool3、无tool基线、岩浆岛 qwen），偷袭 **21.086** / 炮击 **10.509**；4 局全部战败。`old_v1.0` 的早期材料照常评分、进归档表，但**不参与本轮标定**。样本量小、胜负偏置明显，所以 `config.json` 里仍是 `provisional`；补充胜利长局后再重标定。
- 换 `S0` 时同步改 `config.json` 的 `stage3.parameters.ambush.S0` / `barrage.S0` 与 `stage3.calibration`（样本数、来源、胜负分布），报告的 `calibration.baseline` 直接读后者。
- 归档 `_archive/_stage3-baseline.json`（task125 的 7 局：9.642 / 12.155）与 task126 的 9 局自身中位数（17.831 / 19.419）是另外两套语料，不能与本批混用；用哪套 `S0` 要在比较前说清楚。

## 给新一批局打分

整批跑（含归档局）用 [`tools/evaluate_corpus_v2.js`](tools/README.md)：

```powershell
node evaluate/tools/evaluate_corpus_v2.js                 # 扫 evaluate/output 全树
node evaluate/tools/evaluate_corpus_v2.js --match "<对局目录>"
```

它按"live 有 `stage3_meta` 就用 live，否则要求 resim 零分叉"选证据，跑 Stage1-v2 / Stage3 / `total-v2`，写 `<局>/evaluation_v2.json`，并把结论汇总到 `evaluate/output/_v2-summary.json`（含忽略原因与 >15min 调参池的 `S` 中位数）。`evaluate/output/` 不进版本库，公开结论由 `evaluate/tools/build_web_results_v2.js` 写进 `web/resource/results.json`。

## 已知限制

- `S0` 只有 `old_v1.1` 的 4 局且全败；`W_min`、`N_sat`、`G_ref` 等仍是设计先验，没有参考池冻结。
- 资金 v2 的 `λ`、动态 `spendFloor`（设计里的 `κ·收入率`）与向先验收缩公式未定，当前用显式先验并标 provisional。
- 危险区在基地内可能因我方造价过高建不起来（设计已知局限 1）；建造者是第一个死的人时不计分（宁漏勿错）。
- 4P 归档局重放会分叉，只有 2P 的归档局能进 v2 总分。
- 页面上的 `old_v1.0` / `old_v1.1` 只是归档批次标签（按目录），不影响分数口径；`old_v1.0` 不参与本轮调参。
