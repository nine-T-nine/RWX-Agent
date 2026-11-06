# 评估回归测试

这里集中三个阶段与Replay解析器的回归测试。测试验证统计、评分、检测规则和数据读取正确性，不对真实 Agent 样本下结论，也不是对局结束后必须跑的评估步骤。实际使用流程见[总README](../README.md)。

## 运行

从仓库根目录执行，无需启动游戏，无需npm/pip依赖：

```powershell
node --test evaluate/test/test-evaluate.js evaluate/test/test-stage1-metrics.js evaluate/test/test-low-attribution.js evaluate/test/test-events.js evaluate/test/test-stage2-fixes.js evaluate/test/test-score.js evaluate/test/test-score-evidence.js evaluate/test/test-stage3.js evaluate/test/test-stage3-baseline.js evaluate/test/test-stage3-details.js evaluate/test/test-preprocess.js evaluate/test/test-score-match.js evaluate/test/test-score-v2.js evaluate/test/test-paths.js evaluate/test/test-match-materials.js evaluate/test/test-rebuild-telemetry.js
python -W error::ResourceWarning evaluate/test/test_replay.py
```

也可单独运行或通过unittest发现：

```powershell
node --test evaluate/test/test-evaluate.js
node --test evaluate/test/test-stage1-metrics.js
node --test evaluate/test/test-low-attribution.js
node --test evaluate/test/test-events.js
node --test evaluate/test/test-stage2-fixes.js
node --test evaluate/test/test-score.js evaluate/test/test-score-evidence.js
node --test evaluate/test/test-stage3.js
node --test evaluate/test/test-stage3-baseline.js
node --test evaluate/test/test-stage3-details.js
node --test evaluate/test/test-preprocess.js evaluate/test/test-score-match.js
node --test evaluate/test/test-score-v2.js
node --test evaluate/test/test-paths.js evaluate/test/test-match-materials.js
python -m unittest discover -s evaluate/test -p 'test_*.py'
```

## 每个文件验证什么

| 文件 | 主要覆盖 | 输入/副作用 |
|---|---|---|
| `test-evaluate.js` | 19种直接命令与8种间接配置、批量/队列/建筑位置、目标与取消、重复待发歧义、毫秒与旧整秒时间、A/B周期、未知端口请求不中断，以及真实GPT样本的映射分布 | 大部分使用合成命令；可选读取 `evaluate/output/` 中GPT原始commands与已解析JSONL，只读真实输入；报告写到临时目录 |
| `test-stage1-metrics.js` | Stage1 v2 原坐标曲线、开局与离群边界、命令风暴调整、映射健康门不扣 Agent 分，以及完整入口生成指标/分数/HTML | 合成指标与小型 commands/Replay JSONL；报告写到 `agent-tmp/` 临时目录并清理 |
| `test-low-attribution.js` | 底层命令提交者归属：按（单位集合，±1 tick）把 Replay 记录指派到 `native` 行、`replay_unknown` 回退到 keep-set 推断、keep-clear 结束规则、telemetry 只在对局目录内查找 | 合成 `telemetry.jsonl` 与内存底层记录；临时目录写 `agent-tmp/` 并清理 |
| `test-events.js` | 五类检测器的确认/失活、3/10秒开界、区域合并、自动与主动来源、任务/队列/费用/单位上限、过期对象生命周期与去重、AMBUSH 战损/击杀拆分、空窗的 B 相关性计数、FEED 按全部损失分段（100/108/112 秒、两个独立段各自确认）、PRODUCTION_GAP 的三值判定（共享预算/历史动作/完成证明）、经济采样、旧日志关联、frames 行的等价展开与异常拒绝、同一证据重复分析一致 | 构造内存事件/小型JSONL；临时写事件报告，不启动引擎 |
| `test-stage2-fixes.js` | 第二轮回归：偷袭响应按空间关系而非 `kind` 判定、未来/同 tick 应用结果不提前计入、端口日志命令先于同刻记录注入、FEED 合并重算状态、工厂 task 与真实产出证明、严格10秒上界、`--commands`/`--economy` 必需性及经济序列核对、B-81 已死亡 builder 经 `--builder` 进入 STALE_COMMAND、拒绝请求分类/查询分离/与 STALE_COMMAND 去重、重复与已恢复重试的过滤、`actionable` 可扣分视图（timing/未证明/对象续用）、完整/截断/缺失结束标记 | 构造内存遥测；小报告写 `agent-tmp/` 临时目录并清理 |
| `test-score.js` | 两阶段通用曲线原坐标计算、HP 损失和响应系数、过期时长分档、空闲独立曲线、资金平滑/折减/分段、分类封顶和覆盖配置、CLI 报告一致性 | 内存对象；临时报告写 `agent-tmp/` 并清理 |
| `test-score-evidence.js` | B 端点整批工作连续可行性；区间中途缺钱/单位上限/动作不可用；建造落点与维修目标；HP 聚合、生效顺序与过期时间证据去重；空闲段的原生命令触碰排除与工厂"别处在排产"排除（指挥中心不计、类工厂按更宽集合） | 内存遥测；不启动引擎 |
| `test-stage3.js` | 第三阶段缓冲门槛、截断排除、危险区漏斗、HP/护盾/警告/时长/造价公式、时长夹逼、绞肉机按首死亡锚点合并与 S0 provisional 标记 | 合成遥测与内存事件，不启动引擎 |
| `test-stage3-baseline.js` | 第三阶段 `S0` 标定：只用证据完整且重放未分叉的局取 `S` 中位数、分叉局只列不进、空族保留占位值不除零、标定报告的中位数对应 `1-e^-1` 上限、缺报告不按 0 分、冻结 `S0` 给新批次打分（不重算中位数、来源记进 `calibration`）、聚合表与排除原因 | 合成 `stage_3/` 与 `resim/playback.json`，写系统临时目录并清理 |
| `test-stage3-details.js` | 第三阶段逐条明细：`s`→份额按族扣分比例摊、`N`/`E`/`H`/`L`/`W`/`w`/`Tf` 各列、事件时间与单位、绞肉机合并事件与排除原因、分叉局标「排除」但仍列记录、没有 `stage_3` 的局跳过 | 合成 `stage_3/` 与 `resim/playback.json`，写系统临时目录并清理 |
| `test-preprocess.js` | 结尾窗口判定（投降/退出/正常结束/截断/进程中止的优先级与取最早）、裁剪后 telemetry/commands/economy/replay 的一致性、合成 end 行与证据覆盖标注、材料收敛后的命令日志位置、`preprocess.json` 只留一份 | 合成小样本写系统临时目录 |
| `test-paths.js` | 对局路径唯一入口：两种布局（散着/已收敛）都解析到同一个工作区、`prepared/` 输入不拼出第二层目录、`--out` 仍可改向、对局名清洗保留 `--` 与括号、共享/归档目录（`_archive`、`*_old`、`micro`）不算对局 | 临时目录 |
| `test-match-materials.js` | 材料收敛：命令日志与引擎证据被移动而不是复制、外层重复副本被删除、回放改名成 `<对局名>.replay`、只留一份 `preprocess.json`、`meta.json` 的自动字段与人工覆盖、重复运行无副作用、`_old`/`micro` 不碰、只有回放的局不建目录、内容冲突只报告、`--archive` 把 `*_old` 收进 `_archive/` 且默认不动归档 | 临时目录，不碰真实 `evaluate/output/` |
| `test-rebuild-telemetry.js` | 回放-only 材料重建：收据日志补 `command`/`result` 行、refs 带收据时刻的死亡时间、`frames` 压缩行展开、replay ordinal → native `source=agent`/`commandId` 归属、未命中 replay 的请求记为推断拒绝（`inferred:true`）而不是静默接受、晚于回放 `end` 的收据行被丢弃（不把游戏时钟写成倒走） | 合成 commands/Replay/telemetry，写系统临时目录并清理 |
| `test-score-match.js` | 综合分 `0.4/0.6` 权重、版本门（旧版本默认拒算、显式放行后标记）、预处理否决时综合分为空 | 临时目录写两阶段 score.json |
| `test-score-v2.js` | 中央参数账本（每个旋钮都有说明/初值/当前值、模块版本取自 `config.json`）、Stage1-v2 六个特征与三类权重、少样本收缩、无 A 饱和、日活跃度闸门、Stage3 资金 v2 的囤积区间与 `releaseShare`、缺经济证据不按 0 分、`total-v2` 加权与版本门 | 内存命令/经济序列 + 临时目录写 `stage_1_v2`/`stage_3` 报告 |
| `test_replay.py` | 按引擎写侧布局合成二进制，覆盖UTF/gzip、命令各字段、JSONL导出、文本过滤、截断/损坏容错、strict模式；对仓库录像检查布局无漂移、元数据与时间一致 | 合成Replay和输出放临时目录；只读仓库 `replays/*.replay`，真实样本较多时用时更长 |

Node 用例覆盖三阶段统计、评分、事件检测、预处理、路径解析与材料收敛；完整数量以实际输出为准。Python 既有基线为27项。样本要求：

- GPT映射回归依赖 `evaluate/output/` 中的对应原始commands与解析JSONL；目录不存在时skip，具体样本缺失时用例提前返回，因此不能仅凭总通过数认定真实GPT样本已验证。合成用例独立运行。
- Python真实Replay回归在 `replays/` 不存在时skip；目录存在时要求至少一份样本，并对当前完整样本集设置命令总量和元数据断言。精简样本集可能不满足这些断言。
- 所有测试临时文件放仓库 `agent-tmp/` 并自动清理；Python导入缓存可能在 `test/__pycache__` 或 `utils/__pycache__` 出现，可删除。

## JVM侧集成测试

以下Kotlin测试仍放在Gradle标准测试源目录，保持源码发现与现有构建配置；它们验证引擎/CLI旁路，属于本评估体系，但不搬到Node/Python目录：

| 文件 | 用途 |
|---|---|
| [EngineEvidenceTest.kt](../../core/src/test/kotlin/evaluate/utils/EngineEvidenceTest.kt) | 游戏秒采样、暂停、时间跳跃、命令收包状态（含 `--builder` 收包对象）、跨线程关联、死亡去重、跨局文件、初始建造者嵌套读取事务列表不中断采集，以及证据目录命名（显式标签、默认取回放名、`meta` 行记 `label`/`replay`/`gameVersion`） |
| [EngineEvidenceTrimTest.kt](../../core/src/test/kotlin/evaluate/utils/EngineEvidenceTrimTest.kt) | `trimTelemetry` 把连续 frame 行合并为 frames 行：段内恒定步长、变速/跳号分段、单帧原样保留 |
| [RwxCliCommandCaptureTest.kt](../../core/src/test/kotlin/com/corrodinggames/rts/game/ai/custom/rwx_cli_tool/RwxCliCommandCaptureTest.kt) | Socket执行前收包、协议/编码、毫秒、改名与并发、评测开关闸门、仅一份命令TXT且无stats |
| [AppOptionsTest.kt](../../core/src/test/kotlin/io/github/rwx/app/AppOptionsTest.kt) | `--evaluate`开启CLI、默认证据目录、显式目录优先、参数处理 |

需要Java25。只筛选相关集成类可运行：

```powershell
.\gradlew.bat :core:test --tests evaluate.utils.EngineEvidenceTest --tests '*RwxCliCommandCaptureTest' --tests '*AppOptionsTest'
```

修改引擎或CLI实现后，还需按仓库协作规范执行完整 `:core:test`；仅移动本目录的Node/Python文件不改变JVM测试发现。

## 移除的无关演示

原 `evaluate/RL/test.py` 会连接另一套Gymnasium服务并不断下发随机动作，没有断言，也不验证本系统任何指标，已连同 `evaluate/RL/` 的客户端源码一起删除；需要离线数据时直接用 `replay.py` 导出的 JSONL。
