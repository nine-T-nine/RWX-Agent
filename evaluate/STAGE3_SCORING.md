# 第三阶段：延迟严重程度评估

本阶段实现 [`score_design.md`](score_design.md#第三阶段) 的三项规则：空闲移动单位受击、建筑遭炮兵持续攻击、危险区内建造者死亡。它独立输出 100 分制成绩；当前 `score_match.js` 的公榜综合分仍只使用第一、二阶段，因为设计尚未给第三阶段权重。

## 材料与采集

使用当前版本以 `--evaluate <档位>` 运行或用当前 Headless 发行包重放 Replay。`telemetry.jsonl` 除原有行外新增：

| 行 | 内容 |
|---|---|
| `stage3_meta` | 炮兵攻击间隔、危险区半径与建区参数 |
| `stage3_ambush` | 空闲移动单位每次受击及缓冲关闭；建造者执行 build/repair 仍视为空闲，运输中不计 |
| `stage3_barrage` | 建筑遭自行火炮、重型火炮、火炮机甲命中的缓冲与关闭原因 |
| `stage3_zone` | 危险区 create/refresh/check/clear/drop 状态机及每次 E/F/ratio |
| `stage3_builder_death` | 四类建造者的完整漏斗：伤害击杀、运输、覆盖区、age、是否可计分 |

伤害通知同时记录实际 `hpLoss` 与 `shieldLoss`，均在伤害结算前后取真值，过量伤害不计。HP/盾上限和累计造价在缓冲创建时冻结。炮兵 `K` 从游戏单位数据的最大攻击间隔读取，默认原版值为 4 秒，`K_tol=1.3K=5.2 秒`。

危险区严格按设计顺序在同帧批处理：死亡台账 → 刷新已有区 → 4 秒节拍检查 → 按 id 聚类建区 → 判定建造者死亡。区域造价使用游戏真值，不受视野限制；所有阈值与每次判定的 E/F 都写进原始材料。

## 离线流程

预处理后运行：

```powershell
$m = "evaluate/output/<对局名>"
node evaluate/evaluate_stage_3.js "$m/prepared/telemetry.jsonl" --out "$m/stage_3"
node evaluate/score_stage_3.js "$m/stage_3"
```

第一条命令只做材料门槛、缓冲重建和漏斗整理；第二条命令才计分。输出：

| 文件 | 用途 |
|---|---|
| `stage_3/events.jsonl` | 三类规范事件及排除原因 |
| `stage_3/zones.jsonl` | 危险区完整状态机，供人工复核和重新标定 |
| `stage_3/summary.json` | 覆盖、漏斗、区寿命和输入身份 |
| `stage_3/events.txt` | 简明事件时间线 |
| `stage_3/score.json` | 公式参数、逐项中间量、诊断和总分 |
| `stage_3/score.txt` | 人读摘要 |
| `stage_3/timeline.csv` | 可排序的事件表 |

旧 telemetry 没有 `stage3_meta` 时入口会拒绝，不能把“没有采集”解释成“零事件”。被预处理窗口截断且没有关闭行的缓冲会保留为 `INCOMPLETE_INTERVAL`，但不扣分。

## 归档局的加速重放（resim）

`evaluate/output/_archive/` 里的局录制时还没采集第三阶段证据，只能重放补：

```powershell
$m = "evaluate/output/_archive/<对局名>"
python evaluate/utils/evaluate_replay.py "replays/<对局名>.replay" --team <队伍> `
  --commands "$m/commands-<对局名>.txt" --out "agent-tmp/<slug>"
# 播放结果拷进对局目录，原有的 stage_1/ stage_2/ replay/ 保持不动
Copy-Item "agent-tmp/<slug>/engine/match-*/telemetry.jsonl" "$m/resim/telemetry.jsonl"
Copy-Item "agent-tmp/<slug>/engine/match-*/economy.log"     "$m/resim/economy.log"
node evaluate/evaluate_stage_3.js "$m/resim/telemetry.jsonl" --out "$m/stage_3"
node evaluate/score_stage_3.js "$m/stage_3"
```

`resim/playback.json` 记录回放名与 SHA-256、队伍、`checksumWarnings`、引擎提交和耗时，是这一份证据的来源凭证。团队约定：

- 重放窗口止于 `replay_end`（回放里没有 `match_result`），不做 `preprocess` 的尾部裁剪。同一批归档局的 `G` 彼此可比，但和 live 局窗口不完全一致。
- `checksumWarnings > 0` 表示重放与录制分叉，事件只是“这份材料能算出什么”，不当作真实战况。本批 10-27 的三局 4P 都出现分叉（475 / 973 / 2820 条校验不一致；gpt 局首个不一致在第 21227 帧，约 340 s），不进 S0 基线。
- `未完成录制（缺少 end/meta 块）`的回放重放到 EOF 后引擎不退出，拿不到 `replay_end`，该局没有第三阶段材料。

## S0 标定（归档基线）

两项饱和公式的 `S0` 只能用基线 `S` 的中位数。第一次真实运行后：

```powershell
node evaluate/tools/stage3_baseline.js                      # 默认扫 evaluate/output/_archive
node evaluate/tools/stage3_baseline.js --write --out evaluate/output/_archive/_stage3-baseline.json
```

- 只用 `summary.complete=true` 且 `resim/playback.json.checksumWarnings=0` 的局求中位数；证据不全或分叉的局单独列出，不参与。
- 某一族整批 0 条记录时保留占位 `S0=1` 并在结果里标 `fallback`，不会除以 0。
- `--write` 写 `<局>/stage_3/score.calibrated.json` 与 `.txt`；聚合（S 分布、N 直方图、类型占比、危险区漏斗）写到 `--out` 的 JSON 和同名 `.md`。
- `node evaluate/tools/stage3_details.js` 再把每局每条记录的 `L/W/w/Tf/s`、事件时间、单位类型与“份额”摊成 `_archive/_stage3-detail.md`；份额是族扣分按 `s` 的比例归因，不是可以单独相加的分数。
- 标定后的分数仍标 `provisional`：归档语料模型、提示词、时长都不统一，只能当工作标定；受控基线（固定提示词/模型）建成后再替换。
- 给新一批局打分而不再重新标定时，把已有聚合直接冻进去：`node evaluate/tools/stage3_baseline.js --s0 evaluate/output/_archive/_stage3-baseline.json --match <对局目录>… --out <新的聚合> --write`。此时 `score.calibrated.json` 记 `s0Source:"fixed-baseline"` 与来源，分数仍 `provisional`。
- 两批语料的 `S` 分布可能差很多（2026-11-05：归档 7 局中位数 9.642/12.155，当前 9 个标准局自身中位数 17.831/19.419），拿归档 S0 去评当前局会大面积顶到上限；横比时先确认用的是哪套 S0。

## 评分

三项公式与 `score_design.md` 一致，参数也逐项写入 `score.json.parameters`：

- `STAGE3_AMBUSH`：逐记录计算 `E/H/L/W/Tf/w/s`，按对局时长缩放汇总，以 `P_max·(1-exp(-S/S0))` 扣分，上限 60。
- `STAGE3_BARRAGE`：只保留 `N≥2` 或建筑被摧毁的缓冲，不含时长项，同样饱和扣分，上限 60。
- `STAGE3_GRINDER`：同一区内按首个死亡锚点的 5 秒窗口合并，计算 `B/L/SingleMax`，上限 40。

阶段总分为 `max(0, 100 - 三项扣分之和)`。两项饱和公式的 `S0` 目前按设计使用占位值 1，`score.json.calibration.provisional=true`；跑完基线并以各自 S 中位数替换前，第三阶段成绩不得进入正式榜单。可用覆盖文件离线试参：

```powershell
node evaluate/score_stage_3.js "$m/stage_3" --weights stage3-weights.json
```

只有材料收集参数（危险区 X/R/R2/U、检查节拍、死亡窗口、炮兵超时）变化时需要重跑游戏或 Replay；`alpha/beta/W/N/T/价值权重/S0` 与绞肉机的合并、宽限、严重度参数都可直接重跑评分。

## 已知边界

- 危险区触发单位按原版陆军、机甲、实验陆军工厂产物集合判定；自定义 Mod 单位不进入正式评分。
- E 中的炮塔限定为可对地且类型为 turret 的静态单位；指挥中心只按 F 中 99999 造价处理。
- 第三阶段目前没有综合榜权重，也没有已标定 S0；这是明确的发布门槛，不由工具猜测。
- 归档基线（2026-11-05，7 局 2P）里危险区建起 12 个、丢弃 299 次，建造者死亡 30 次却没有一次算分。丢弃几乎全是 `ENEMY_MINIMUM`（E<1500）或 ratio 不足：2P 小图里我方造价占比高、指挥中心按 99999 计入 F，`U_create=0.7` 很难满足；建造者又基本死在自己基地附近（F 巨大，天然不建区）。这是参数/布景问题而不是实现问题，调 `E_min` / `U_create` 前不要把它读成“信息过期延迟不存在”。
- 归档基线的 `N` 中位数：偷袭 4、建筑炮击 5（各 186 / 90 条）。炮击 `N_sat=4` 已经饱和，符合设计预期（炮兵射速慢）；偷袭 `N_sat=8` 尚未饱和，说明多数受击记录在 4–5 次之前就结束了。
- 用归档语料中位数（偷袭 9.642 / 炮击 12.155）标定后，中位局每族各丢约 38 分（63% 上限）；7 局标定总分从 0 到 72.8，其中 4 局低于 15。这是 `P_max·(1−e^(−S/S0))` 在 `S=S0` 处的数学性质，不是叠加 bug；是否改用均值或更高分位需要另行决定。
