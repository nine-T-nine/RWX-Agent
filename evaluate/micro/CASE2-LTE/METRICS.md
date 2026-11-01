# CASE2-LTE 离线指标

引擎只写事实，`evaluate.py`加载`metrics/*.py`插件计算七项指标；无加权总分。schema=`rwx-micro-lte-metrics/2`，metrics每项含tier/sources/value，value.status为OK/N/A/ERROR。

## 1. 证据与时间

| 文件 | 用途 |
|---|---|
| meta.json | task/seed、builder、初始资金、资源点、目标、地图哈希、经济表、动作白名单、预算 |
| gateway.jsonl | 请求requestId=seq、class/name/raw、wallRecvMs/wallReplyMs、接收时gameMs/tick、status/error |
| match.replay | 引擎真正录制的底层命令；拒绝请求不在其中 |
| engine/lifecycle.jsonl | instruction阶段、native录制关联、收入锚点 |
| violations.jsonl | 违规及执行前拦截证据 |
| result.json | authoritative success/timeout/error、目标时刻与终态 |

所有wallMs使用run初始化时的System.nanoTime原点，包含加载，不使用系统日历时间。第一请求触发预算；beginWallMs触发任务elapsed。gameMs每tick+16，名义1×60tick/墙钟秒。默认游戏上限20分钟、墙钟1320s；结算后两条路径都等待10秒再退出（`RWX_MICRO_EXIT_IN`）。Replay只有tick，没有原生墙钟；native事件在实际ReplayWriter插入处附加同源wallMs，解析时核对tick和签名。

## 2. T1指标

`outcome`：结局、endReason、初始/最终/目标收入、targetReachedGameMs/WallMs、gameElapsedMs、wallElapsedMs、remainingResources、unusedCredits。目标时刻来自引擎afterTick，跟Agent何时观察无关。

`income_anchors`：初始收入0及每次实际收入变化（含下降）；每行tick/gameMs/wallMs/incomePerSecond/deltaIncome/cause[]/buildingIds[]、锚点index、相邻间隔和begin相对时刻。未知原因写unknown；同tick完成可合并，保留全部原因/建筑。每tick读取O(1)阵营收入缓存，只在变化时写。

`instruction_lifecycle`：每条Gateway B的issued时间、accepted/rejected、order_started、pathing/moving/arrived/warming_up/constructing/queued/upgrading/waiting_for_credits及终态。阶段开始与`<phase>_end`成对，分段累计gameMs/wallMs；移动结束是创建地基进入施工位置，不声称已到矿脉中心。build完成须真实施工进度≥1并使收入生效；upgrade完成须实际类型变为下一标准等级并使收入生效。费用来自引擎扣款前后差，非资金总差推测。

终态为completed/failed/cancelled/superseded/timeout/rejected。端到端issueToComplete与execution字段当前表示到终态的区间；统计完成耗时只取completed。等待资金单列，包含在端到端区间。标准升级资金不足在Gateway拒绝，没有伪造等待队列；建造缺款由引擎重试并记录waiting_for_credits。

匹配优先nativeIndex（ReplayWriter插入顺序，0起）+tick+actor+建造类型/坐标/队列数或action签名。索引签名不一致、重复占用索引、候选不唯一分别unmatched/ambiguous；没有强行最近匹配。Gateway中未受理请求不要求Replay。成功受理但没有原生命令必须显示unmatched（例如执行前被新请求覆盖）。同tick多个建筑完成的收入合并标combined_tick，不虚构每条指令分摊金额。

`violations`：次数、类型、首时刻、完整记录、allRejectedBeforeExecution、unexpectedNativeCount、unmatchedMappings、violationEnteredReplay。任何不能对应授权记录的Replay命令在diagnostics标severe/native_authority_mismatch；缺少Replay时N/A，不能声称没有违规流入。

## 3. T2指标

`ab_timing`仅对成功受理的A/B排序：

- A→B：该B之前最后一条已回复A到B接收。
- B→next A：B回复到下一条A接收。
- B→B、A→A：相邻同类请求接收间隔。
- decisionCycles：对每个B关联之前最后A和之后首A；缺一不补造。

每类输出n/mean/median/p50/p90/max/samples/outliers。分位数线性插值；离群为大于Q3+1.5×IQR。样本保留request ID，空样本统计为null。

`wake_alignment`：仅完成指令，B回复后首次A与完成时刻比较；提前则wakeBeforeCompleteMs，完成后首次A给wakeAfterCompleteMs，另列pollsBeforeComplete及bToNextAMs/completeFromReplyMs。结束后自动退出可能没有完成后的A，值为null，不能算零延迟。

`agent_timing`：所有A/B/C计数、status计数、Gateway处理耗时分布、invalid_request比例、rejected比例及AB间隔。B受理不代表执行成功。A→B包含模型、Runtime、工具调度，不等同纯模型思考时间；没有读取模型内部ETA，也不声称AB序列能还原显式预测。

## 4. 运行与边界

```powershell
python evaluate/micro/CASE2-LTE/evaluate.py evaluate/output/micro/CASE2-LTE/<run-directory>
```

输出eval/metrics.json、report.txt、report.html。同一证据重复离线计算得到相同指标。只比较同task+seed的Agent/Greedy，不合并总分。无独立re-sim、无GUI验收；生命周期依赖live记录，Replay用于核对底层命令，不单凭Replay推导完成时间。
