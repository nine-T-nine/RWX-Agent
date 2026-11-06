# 第一阶段 v2：交互节奏评分（`stage1-score-v2`）

本文件说明 [`score_design.md`](score_design.md#第一阶段) 第一阶段 v2 的落地口径。v2 与旧版完全换了一套问题定义：不再按 12 个行为区间扣分，而是衡量 **启动、回合延迟、空窗** 三类节奏，`100 − Σ组权重 × 组内平均严重度`。

> 历史命名容易混淆：[`stage1-metrics.js`](stage1-metrics.js) 的版本号仍写着 `stage1-v2`（早期把 12 项公式当成 v2），它实现的是**旧版公式**；真正的设计 v2 在 [`stage1-v2.js`](stage1-v2.js)，版本号 `stage1-score-v2`。旧报告只作 `old_v1.1` 对照，`score_match_v2.js` 不接受它的版本号。

## 事件定义

- **B 簇**：相邻 B（底层指令）间隔 ≤ `clusterGapSeconds`（5 s）合并为一簇，取簇首与簇尾。
- **A 块**：两个相邻 B 簇之间的所有 A（查询类）合并成一块，取块内第一条 A。
- **回合延迟**：`Γ_BA` = 左簇尾 → A 块首；`Γ_AB` = A 块首 → 右簇首。两段和恰好是两个 B 簇的间隔，在空档里塞 A 只能让时间在两段之间转移。
- 两个 B 簇之间没有 A，这个回合不产生 `f2`/`f3` 样本。
- **空窗**：B 簇间空窗（`f5`）与底层指令间空窗（`f6`）都包含末尾一段（最后一条指令 → 评分窗口结束）。
- A 类不进入 `f5`/`f6`，所以发 A 刷不了空窗项。

## 特征与计分

`u = clip((ln(1+x) − ln(1+lo)) / (ln(1+hi) − ln(1+lo)), 0, 1)`；`x ≤ lo` 为满分、`x ≥ hi` 扣满该特征。`g_λ(u) = (e^{λu} − 1)/(e^{λ} − 1)`，`λ→0` 时退化成 `u`。

| 特征 | 定义 | 统计量 | 组 / 权重 | 参数（`config.json`） |
|---|---|---|---|---|
| `f1` | 首条底层指令时间 | 单值 | 启动 / 20 | `featureThresholdsSeconds.f1 = [3, 90]` |
| `f2` | `Γ_AB` | 中位数 | 回合延迟 / 40（与 `f3` 各 20） | `f2 = [5, 30]` |
| `f3` | `Γ_BA` | 中位数 | 同上 | `f3 = [5, 35]` |
| `f5` | B 簇间空窗（含末尾） | 时间加权 P90 | 空窗 / 40（与 `f6` 各 20） | `f5 = [5, 60]` |
| `f6` | 底层指令间空窗（含末尾） | 时间加权 P90 | 同上 | `f6 = [3, 45]` |
| `f4` | A 块间空窗 | 时间加权 P90 | 仅诊断（权重 0） | `f4 = [5, 60]` |

**时间加权 P90**：每个间隔以自身长度作权重，取累计权重达 `weightedQuantile`（0.9）处的间隔值。含义是"随机抽一秒，它所在的空窗有多长"，批量下令后长停照样会拉高。

`总分 = 100 − Σ_g W_g · mean_{k∈g} g_λ(u_k)`，三组权重来自 `groupWeights`（20/40/40），组内平均。

## 边界规则

- 评分窗口 `G < minimumDurationSeconds`（300 s）不评分（`eligible=false`）。
- 某特征样本数 `n < minimumSamples`（5）：`u ← (n·u + (5−n)·1) / 5`，缺样本按停滞处理并标 `lowConfidence`。
- 没有任何 A：`f2`/`f3` 直接 `u = 1`。
- **活跃度闸门**：底层指令数 / 分钟低于 `minimumNativeCommandsPerMinute`（8）时，得分封顶 `activityGateScoreCap`（60）。原生计时优先取 `source ∈ {agent, pending, unknown}` 的 `native` 行；回放-only 材料没有这些行时退回全部非 engine 行，并在 `source.nativeScope` 标注用了哪一种。

## 输出

```powershell
node evaluate/score_stage_1_v2.js <对局目录> `
  --commands <commands-*.txt> --telemetry <telemetry.jsonl> --cut-ms <窗口 ms>
```

写 `stage_1_v2/score.json` 与 `score.txt`：

| 字段 | 含义 |
|---|---|
| `score` / `rawScore` / `deduction` | 最终分（含闸门）、曲线前分数、`100 − score` |
| `groups.{startup,cycle,silence}` | 组权重、组内平均严重度、扣分 |
| `features.{f1..f6}` | 原值、样本数、`u`、`g_λ(u)`、是否低置信、是否仅诊断 |
| `activity` | 每分钟底层指令、阈值、上限、是否触发 |
| `parameters` | 本局实际使用的参数快照（来自 `config.json`） |

## 状态与限制

- `config.json` 里 `featureThresholdsSeconds`、`lambda`、`minimumNativeCommandsPerMinute`、`activityGateScoreCap` 都标 `provisional`：设计只给了搜索范围/先验，没有参考池冻结。当前 `lambda=0`（线性）、闸门 8/min、封顶 60。
- 阈值应按设计用参考池（人类 + 固定基线 Agent）标定：`lo=P25`、`hi=P95`，发布新版本时才重估，批内冻结。
- `parameters` 会原样写进每局报告，所以事后能复核这一局用的是哪套先验。
