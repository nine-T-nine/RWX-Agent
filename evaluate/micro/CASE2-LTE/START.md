# CASE2-LTE 参与说明

使用唯一建造者，在资源点建造和升级提取器。首次达到本局目标 `incomePerSecond` 即成功。

仅开放 **realtime** 模式。执行 `begin` 后，游戏以 1× 速度持续运行。

## 0. 启动与连接

从仓库根目录启动：

```powershell
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE2-LTE --case-args task=2_5_3,seed=1,track=realtime --data-dir=agent-operation/tmp/lte-a --cli-port-file=agent-operation/tmp/lte-a/port'
```

等待以下就绪消息：

```text
RWX_MICRO_READY case=CASE2-LTE phase=briefing tick=0
```

如果评测方已经启动实例，直接使用其提供的端口文件连接，不要重复启动或硬编码端口。

启动 CLI 客户端：

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/lte-a/port --stdio
```

整个对局保持同一个客户端和标准流连接：

- 等待 stderr 输出 `ready`。
- 每次向 stdin 写入一行命令并 flush。
- 每次从 stdout 读取一行 JSON。
- 外层 `reply` 是字符串；游戏观察使用 `--json` 时，需要再次解析。
- 客户端不会自动等待任务完成或轮询，观察时机由 Agent 自行决定。

## 1. 任务与经济参数

任务 ID 格式为 `2_Y_T`：

- Y：矿脉数量，4～8。
- T：经济档次，1～5。
- 初始资金：2100。

本局的目标收入不在此给出。进入评测对局后，`rwx-micro-2-state --json` 会输出 `targetIncomePerSecond`（同一观察里还有 `targetComposition` 和 `targetReached`），以游戏输出/打印的目标值为准。

### 地图与资源

- 正方形草地地图，边长约 55～73 格，每格 20 世界单位。
- 建造者固定出生在地图中心。
- 出生点周围半径 15 格内没有矿脉。
- 矿脉位置随 seed 变化。
- 没有敌人、其他单位、建筑或额外补贴。
- 合法建造会自动寻路，不开放直接移动。

### 提取器

| 等级 | 收入 | 建造/升级费用 | 基础工作量 |
|---|---:|---:|---:|
| T1 | 8 | 700 | 1000 tick |
| T2 | 12 | 1400（T1 升级） | 约 1667 tick |
| T3 | 20 | 4000（T2 升级） | 约 3333 tick |

T1 只能在空矿脉上直接建造，T2、T3 必须逐级升级。

基础工作量不包含寻路、转向和建造者 30 tick 预热，实际完成时间以游戏观察为准。

**经济规则：**

- 建造费用在地基创建前一次性支付，资金不足时订单会等待。
- 升级资金不足时请求会被拒绝，需要 Agent 在资金充足后重新请求。
- 建造者施工与提取器自身升级可以并行，不同提取器之间也可以并行升级。
- 新建任务会替换建造者当前任务，未完成的地基不会自动续建。
- T3 超频、护盾升级及相关降级操作全部禁止。

注意：目标按照游戏提供的 `incomePerSecond` 字段判定。该字段不是实际每墙钟秒到账金额；默认资源结算周期为 40 tick。

## 2. 参与流程

首先查看可用命令和当前状态：

```text
rwx-micro-2-help
rwx-micro-2-state --json
rwx-micro-2-builder --json
rwx-micro-2-resources --json
```

准备完成后，显式开始对局：

```text
rwx-micro-2-begin
```

随后根据当前观察执行建造或升级：

```text
rwx-micro-2-build --resource R1
```

`R1` 仅为语法示例，必须使用实际观察到的资源编号。

升级时使用 `rwx-micro-2-upgrade`，目标为 `resources` 中提供的真实 `buildingId`。

**注意：**

- `ok: submitted requestId=N` 仅表示请求已受理，不代表施工已经开始或完成。
- 应根据实际位置、施工进度和资金决定下一次观察与操作。
- 不需要依赖预估 ETA。
- 完整命令参数见 `commands.md`。

## 3. 时间与限制

| 项目 | 限制 |
|---|---:|
| 游戏时间上限 | 1200 game-s（20 分钟） |
| 墙钟预算 | 1320 秒 |
| ready 后无请求时间上限 | 600 秒 |
| 游戏速度 | 固定 1× |

时间规则：

- 墙钟预算从第一条 CLI 请求开始计算，包含 help、观察、ping 和错误请求。
- 游戏时间及任务完成耗时从 `begin` 开始计算。
- 1 tick = 16 game-ms，1× 约为每墙钟秒 60 tick。
- 观察和工具调用期间游戏不会暂停。

**允许使用的命令仅有：**

- `rwx-micro-2-*`
- `rwx-ping`

禁止使用其他通用游戏命令、任意动作 ID、建筑类型、坐标或其他单位。

本场景不提供暂停、加速、等待、步进、重启、投降或预测功能。

违规操作会在执行前被拒绝并单独记录，不改变胜负；普通参数错误记为 `invalid_request`。

## 4. 对局结束

结束条件：

- `success`：首次达到目标收入。
- `timeout`：预算或游戏时间耗尽。
- `error`：场景或运行异常。

达到目标或时间耗尽后：

1. 有窗口时播放原版结算动画。
2. 打印 `RWX_MICRO_EXIT_IN 10..1`。
3. 10 秒后自动退出。

Headless 模式同样等待 10 秒退出，并在结束时落盘结果、打印 `RWX_MICRO_RESULT`。

结束后不再接受操作，不要依赖结束后继续查询结果。

**参赛规则：** 对局过程中不得读取源码、隐藏证据、其他 run 或 `RWX_MICRO_RUN` 指向的目录。所有详细评测数据由评测方在对局结束后离线分析。