# CASE4-Retreat 参与说明

> 这是设计版参与说明。正式实现前必须补齐游戏/墙钟时间上限、A/B 环带单位、维修湾布局和我方全灭规则；这些参数目前未由需求草稿明确。

## 1. 启动

从仓库根目录启动。示例 `4_1_1`、`seed=1` 仅为语法示例。

```powershell
# Headless
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE4-Retreat --case-args task=4_1_1,seed=1,track=realtime --data-dir=agent-operation/tmp/retreat-a --cli-port-file=agent-operation/tmp/retreat-a/port'

# Window GUI
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE4-Retreat --case-args task=4_1_1,seed=1,track=realtime --data-dir=agent-operation/tmp/retreat-a --cli-port-file=agent-operation/tmp/retreat-a/port'
```

等待就绪消息（实际格式以实现为准）：

```text
RWX_MICRO_READY case=CASE4-Retreat phase=briefing tick=0
```

建议先在 briefing 阶段观察，再调用 `rwx-micro-4-begin` 开始实时任务。begin 后游戏以 1× 连续运行，不支持 pause、step 或 speed。若最终决定采用进入即运行，必须同步修改本文件、README 和命令手册。

## 2. 连接 CLI

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/retreat-a/port --stdio
```

整局保持同一个 CLI 客户端和标准流：stderr 等待 `ready`；stdin 每行一条命令并 flush；stdout 每次读取一行 JSON。带 `--json` 的观察命令需要解析外层 reply 中的 JSON。

## 3. 任务目标

任务 ID：`4_<type>_<level>`。当前按 level=`a=1..3`、敌军数量乘以 a 设计，最终以评测方确认的配置为准。

| 类型 | 我方单位 | 维修湾 | 敌方配置（不向 Agent 公开） | 迷雾 |
|---|---:|---:|---|---|
| 1 | 屏蔽气垫坦克 ×2 | ×2 | T2 机枪 ×5a、T1 电击塔 ×3a | 无 |
| 2 | 屏蔽气垫坦克 ×2 | ×4 | T2 机枪 ×3a、T1 火炮 ×4a、T2 电击塔 ×2a | 普通持续迷雾 |
| 3 | 屏蔽气垫坦克 ×3 | ×4 | T3 机枪 ×a、T2 火炮 ×a、T2 机枪 ×5a | 普通持续迷雾 |

你只会收到任务目标：**摧毁左上角区域内的所有敌人，并妥善利用右下角维修湾恢复部队。** 不会收到敌军配置、数量或隐藏坐标。迷雾下的敌人只能在游戏实际提供可见信息后查询。

## 4. 地图与维修

- 地图大小 180×180 格；敌方炮塔位于左上角区域，我方维修湾和初始坦克位于右下角。
- 维修湾呈折角布局，坦克出生在维修湾附近。实际几何布局和坐标以地图/单位观察为准。
- type 1 无迷雾；type 2/3 使用普通持续迷雾。
- `rwx-game-map-show` 可按坐标查看地形窗口；`rwx-game-unit-list --owner self --full --json` 可查看己方单位/建筑；`--owner enemy` 只返回当前可见敌方。
- 维修湾的治疗半径、速率和触发方式以游戏实际机制为准。不要把“进入范围”自动等同于“已恢复 HP”；应观察单位 HP 变化。

## 5. 推荐参与顺序

```text
rwx-micro-4-state --json
rwx-game-map-show
rwx-game-unit-list --owner self --full --json
rwx-game-unit-list --owner enemy --full --json
```

确认己方坦克、维修湾和当前可见敌情后：

```text
rwx-micro-4-begin
```

开始后根据敌方可见信息推进。受损单位可用 `rwx-game-order-move` 撤回维修湾；攻击使用 `rwx-game-order-attack` 或 `rwx-game-order-attack-move`。维修是否需要额外指令以本局命令手册和实际观察为准。

## 6. 时间与限制

当前设计草稿没有给出游戏时间上限、墙钟预算或超时倍率。正式评测参数必须由评测方通过 task 配置提供，并由 `rwx-micro-4-state` 显示剩余时间；不能自行假设 20 分钟或照搬 CASE2 的 20 分钟限制。

只开放本 Case 命令手册列出的观察和战斗指令。禁止建造、生产、升级、回收、使用其他单位、使用全局自动化或通用命令绕过白名单。只有 realtime；begin 后游戏持续运行。

## 7. 对局结束

- `success`：左上角目标区域内本局全部敌方目标被摧毁。
- `timeout`：配置的游戏/墙钟预算耗尽。
- `error`：场景或运行异常。
- 我方全部单位阵亡是否作为单独 `defeat` 条件，尚待需求确认；在确认前不得自行加入。

窗口模式播放结算动画；Headless 输出结果；两者均按 CASE2 的结果落盘与退出倒计时约定处理。正式结束后不再接受操作。

参赛期间不得读取源码、隐藏 meta、EngineEvidence、其他 run 或 `RWX_MICRO_RUN` 指向的目录。所有敌军配置、炮塔受伤、维修会话和战损统计由评测方在对局结束后离线分析。

