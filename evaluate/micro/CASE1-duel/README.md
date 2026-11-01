# CASE1-duel(单挑)

## 任务目标

Agent操控给定的单位消灭敌人

## 配置

地图：
1.长和宽分别在80格和120格之间随机取值，为草地地块，并随机生成若干悬崖、水域（随机扰动）。
- 在地图的2个点位（长的一边|东西方向|南北方向：-A--B-，短的一侧取中间值）分别生成两个阵营的初始单位
- 随机扰动分三步：① 先定数量——悬崖 5-10 个、水域 5-10 个，合计 N=10-20；② 用 **Best-candidate 采样**（泊松圆盘的实用替代）一次性撒 N 个中心：**第一个中心固定为"我方出生点朝敌方走 1/3 或 1/4 处"、且必定是水域**（保证 A-水域--B 的开局结构），其余中心在候选点里挑"到已选中心最近距离"最大的那个，分数带 ±15% 抖动，目标间距 `0.65×√(WH/N)`，中心离出生点至少 8 格；③ 每个中心用**随机边界生长**长出 10-20 格：每次在当前边缘集合里随机挑一个格子并入区域，再把它的空地邻居加进边缘集合（不是 FIFO，所以形状不规则）。区域之间额外要求**边界最小间距 5 格**（逐格检查，长不进去就换方向；仍失败则换中心重试）。悬崖每个格子按"远离区域重心"的方向取 8 个朝向之一（cliff 图集 gid 200+facing）；坦克（LAND）无法进入悬崖格，机甲（OVER_CLIFF）可以通过
2.环湖地图：'evaluate/resource/maps/环湖_单挑.tmx'
- 地图上已经放置有 team0 和 team1 各5个指挥中心（team0 为我方、team1 为敌方；隐藏 set 层的 team2 中心运行时不会加载）。保留 team0 的 a 个中心并替换为机枪机甲，team1 保留 b 个并替换为猛犸坦克；除选中的中心外，其他单位一律删除。

(我方)机枪机甲xa vs (敌方)猛犸坦克xb
level1: a=b+1,1<=b<=4（即 2<=a<=5）
level2: a=b,1<=a<=4
level3: a+1=b,1<=a<=4（即 2<=b<=5）
level4: a+2=b,1<=a<=3（即 3<=b<=5）

a、b 各自最大 5，但不会同时为 5；环湖每方 5 个中心，保留 a/b 个，其余中心与所有未选中单位删除。类型 1 的随机图保证两个出生点对 LAND 单位连通，并在开局用引擎寻路复核；不连通会确定性重试，不带着无效布景开局。

任务id为："1_<地图类型|1|2>_<level|1|2|3>"

## 评测

- 时间限制：对局**游戏时间上限 10 分钟**（`gameLimitSeconds` 默认 600）；Agent 的思考墙钟预算是另一本账，默认 1200 秒，从第一条 CLI 请求起算。两者任一达限即 `outcome=timeout`，以启动参数为准。
- 只给有限的命令集（`rwx-micro-1-*` + `rwx-ping`/`rwx-micro-1-help`，见 [commands.md](commands.md)）。
- 评估指标（完整定义与阈值见 [METRICS.md](METRICS.md)）：

| 层 | 指标 | 口径摘要 |
|---|---|---|
| T1 | outcome | 结局、双方存活、HP/护盾损失比例、delta、清场/存活时长 |
| T1 | cadence | 我方命令间隔 p50/p90/max、交战期每分钟命令数、最大无令间隔 |
| T1 | ENGAGE_LAG | 首次交火→首条含我方存活单位的 B 命令；ANSWERED/LATE/UNANSWERED/PREEMPTED |
| T1 | LOWHP_LAG | HP 下穿 50%/25%→首条含该单位的命令 |
| T1 | IDLE_IN_COMBAT | 交战期 order=idle 且无目标/2s 未开火，持续≥2s |
| T1 | STALE_COMMAND | 命令引用已死/不存在单位 |
| T1 | FIRE_UPTIME | 射程内有敌时处于开火状态的时间占比与中断次数 |
| T1 | FREE_FIRE | 我方伤害中攻击者处于所有存活敌人射程外的比例 |
| T2 | agent_timing | A/B/C 计数、A→B 思考墙钟、B→A 等待、观察陈旧度、错误/拒绝/跳过比例、敌方武器轮数 |
| T3 | turns | pause 赛道回合数、每回合墙钟、每回合 B 命令数、空回合比例、ENGAGE/LOWHP 的回合延迟 |

- 人类基线：同一布景的人类回放走 replay-only 路径；key 为 `<task>:<seed>`，每个组合单独打一局，登记在 [baselines/index.json](baselines/index.json)。只有 T1 可比；人类只有 realtime 1×，pause Agent 的 `cadence`/`turns`/`thinkingWallMs` 不可比。GUI 人类实玩仍未验证，且没有强制禁暂停/变速。
- 不折算总分；只在同一 task+seed、同一 track 内比较：win 优先，timeout 不高于 loss，再看 delta。

## HTML 总结

```powershell
python -B evaluate/micro/CASE1-duel/report_html.py
```

读取 `evaluate/output/micro/CASE1-duel/<run>/` 已有的 meta/result/gateway/match.replay，为每个 run 生成 `reports/<run>.html`，并生成汇总 `reports/index.html`（评测维度、run 矩阵、时长/请求/原生命令/延迟/结局图表）。图表是内联 SVG，无外部依赖，可离线打开；悬停数据点可看精确值。`--out` 可改输出目录。
