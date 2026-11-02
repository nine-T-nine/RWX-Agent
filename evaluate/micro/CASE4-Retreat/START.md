# CASE4-Retreat 参与说明

## 1. 启动

从仓库根目录启动。示例 `4_1_1`、`seed=1` 仅为语法示例；正式评测由评测方指定 task、seed 和端口文件。

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

本 Case 先停在 briefing（默认 180 秒），调用 `rwx-micro-4-begin` 后以 1× 连续运行，不支持 pause、step 或 speed；briefing 超时会自动开始并记录原因。

## 2. 连接 CLI

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/retreat-a/port --stdio
```

整局保持同一个 CLI 客户端和标准流：stderr 等待 `ready`；stdin 每行一条命令并 flush；stdout 每次读取一行 JSON。带 `--json` 的观察命令需要解析外层 reply 中的 JSON。

## 3. 任务与目标

任务 ID：`4_<type>_<level>`，type 1～3，level 1～3（敌军数量随 level 倍增）。

| 类型 | 我方单位 | 维修湾 | 迷雾 | 游戏上限 |
|---|---:|---:|---|---:|
| 1 | 屏蔽气垫坦克 ×2 | 2 | 无 | 15 分钟 |
| 2 | 屏蔽气垫坦克 ×2 | 4 | 普通持续迷雾 | 20 分钟 |
| 3 | 屏蔽气垫坦克 ×3 | 4 | 普通持续迷雾 | 25 分钟 |

目标是：**摧毁左上角目标区域内的全部敌人，并妥善利用右下角维修湾恢复部队。**
你不会收到敌军配置、数量和坐标；迷雾下的敌人只能依赖游戏实际提供的可见信息。

## 4. 地图与维修

- 地图 `a × b` 格（`a`、`b` ∈ `[75,80)`，由 seed 决定），全草地，无水域/岩浆/悬崖/资源点。
- **公开目标区域**：地图左上角 3/5 矩形——左上顶点与地图左上角重合，宽、高各为地图的 3/5。所有敌方炮塔都在这个矩形内；我方维修湾和初始坦克位于右下角。
- 维修湾自动治疗范围内的己方单位，只恢复 HP、不恢复护盾；是否需要额外指令、治疗半径与速率以游戏实际机制和观察为准。
- type 1 无迷雾；type 2/3 使用普通持续迷雾。
- `rwx-game-map-show` 可按坐标查看地形窗口；`rwx-game-unit-list --owner self --full --json` 可查看己方单位/建筑；`--owner enemy` 只返回当前可见敌方。
- `rwx-game-unit-list --json` 的快照不含护盾字段，战损请以 HP 变化为准。

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

开始后根据敌方可见信息推进。受损单位可用 `rwx-game-order-move` 撤回维修湾；攻击使用 `rwx-game-order-attack` 或 `rwx-game-order-attack-move`。维修是维修湾的自动行为，不需要单独指令。

## 6. 时间与限制

- 游戏上限 type 1/2/3 分别为 15/20/25 分钟；墙钟预算 = 游戏上限 + 120 秒；剩余时间由 `rwx-micro-4-state` 显示。
- 只开放本 Case 命令手册列出的观察和战斗指令。禁止建造、生产、升级、回收、使用其他单位、使用全局自动化或通用命令绕过白名单。
- 只有 realtime；begin 后游戏持续运行，不得暂停、步进、加速或重启。

## 7. 对局结束

- `success`：左上角目标区域内本局全部敌方目标被摧毁。
- `defeat`：我方坦克全部阵亡。
- `timeout`：配置的游戏/墙钟预算耗尽。
- `error`：场景或运行异常。

窗口模式播放结算动画；Headless 输出结果；两者均按 CASE2 的结果落盘与退出倒计时约定处理。正式结束后不再接受操作。

参赛期间不得读取源码、隐藏 meta、EngineEvidence、其他 run 或 `RWX_MICRO_RUN` 指向的目录。所有评测数据由评测方在对局结束后离线分析。
