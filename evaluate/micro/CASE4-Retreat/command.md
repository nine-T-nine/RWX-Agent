# CASE4-Retreat 命令手册

## 1. 命令总览

| 类别 | 命令 | 用途 |
|---|---|---|
| A | `rwx-micro-4-state [--json]` | 任务阶段、时间、目标完成状态（不泄露敌军组成） |
| A | `rwx-game-map-show [参数...]` | 复用完整游戏地图/地形/fog 查询 |
| A | `rwx-game-unit-list --owner self|enemy --full --json` | 己方全部单位/建筑，或当前可见敌方 |
| A | `rwx-game-unit-show --id <id>` | 单位/建筑详情 |
| A | `rwx-game-threat-list --x X --y Y [--radius R] [--cluster] [--json]` | 查询指定区域当前可见敌情 |
| A | `rwx-game-map-path --unit <id> --x X --y Y` | 查询静态地形可达性和路径长度 |
| B | `rwx-game-order-move --units <ids> --x X --y Y` | 移动己方坦克，常用于撤回维修湾 |
| B | `rwx-game-order-attack --units <ids> --target <id>` | 攻击当前可见敌方目标 |
| B | `rwx-game-order-attack-move --units <ids> --x X --y Y` | 向目标区域推进并交战 |
| B | `rwx-game-order-stop --units <ids>` | 停止己方单位当前任务 |
| C | `rwx-micro-4-begin` | 开始 realtime 对局 |
| C | `rwx-micro-4-help [--list|章节编号]` | 帮助 |
| C | `rwx-ping` | 探活 |

只有以上白名单命令可用。若源码核实维修湾需要显式 repair 命令，才可增加一个只允许本局坦克与本局维修目标的受限接口；不得默认开放通用 repair、build、action 或自动化命令。

## 2. 通用约定

- A 只读，不进入 Replay；B 通过 Case 授权后进入真实引擎命令队列和 Replay；C begin 只开始一次。
- 建议 phase：`briefing/running/ended`。briefing 可观察；begin 后 1× realtime 持续运行。
- 每条观察包含 `status`、`tick`、`gameMs`、`wallMs`。wallMs 使用 run 初始化时同一个单调墙钟原点。
- `submitted` 只代表命令已提交，不代表单位已经移动、抵达、开火或摧毁目标。
- 文本错误以 `error:` 开头；普通参数错误和明确越权分别使用 `invalid_request` 与 `violation`。

## 3. 地图查询

复用完整游戏的 `rwx-game-map-show` 参数和地形编码：

```text
rwx-game-map-show [--spawns] [--x X --y Y] [--half-width W --half-height H] [--unit-land | --unit-air | --unit-underwater | --unit-building]
```

- 默认显示地图中心 17×17 单字符 ASCII 地形图并自动按迷雾遮罩；`--x/--y` 指定世界坐标中心。
- `--half-width/--half-height` 必须同时提供，范围与完整游戏手册一致；超出地图会裁剪。
- 加 `--unit-land/--unit-air/--unit-underwater/--unit-building` 之一时每格变成 5 字符实体编码图（互斥）。
- Case 可以简化与任务无关的统计（例如资源池/经济统计），但地形分类、坐标换算、窗口裁剪和 fog 遮罩必须复用完整游戏实现，不能另造不一致的地图算法。
- v1 不承诺地图内嵌陆军/空军单位图层；单位位置以 unit-list 为准。
- 地图查询不能泄露迷雾内敌方炮塔的坐标、类型、数量或隐藏 ID。

## 4. 单位与敌情观察

### 4.1 `rwx-game-unit-list`

建议调用：

```text
rwx-game-unit-list --owner self --full --json
rwx-game-unit-list --owner enemy --full --json
```

`--full` 用于取消默认 HP 筛选。敌方列表必须遵循当前游戏可见性：只返回当前可见实体。不能为了方便评测而将私有敌军列表返回给 Agent。

### 4.2 `rwx-game-unit-show`

仅允许查询己方单位/建筑和当前可见敌方实体。隐藏敌方 ID 查询应返回统一 `unknown_or_not_visible`，不得区分“存在但隐藏”和“不存在”。

### 4.3 `rwx-game-threat-list`

只返回查询范围内当前可见敌方实体的威胁统计。不能扫描隐藏敌军后返回总数或隐藏炮塔类型。

## 5. 战斗操作

### 5.1 撤退/维修

```text
rwx-game-order-move --units <tankId[,tankId...]> --x <repairX> --y <repairY>
```

仅允许本局屏蔽气垫坦克。坐标应为维修湾附近合法可达位置。移动命令不代表已经进入维修范围或已经开始治疗；Agent 应继续观察单位位置和 HP。

### 5.2 攻击

```text
rwx-game-order-attack --units <tankIds> --target <visibleEnemyId>
rwx-game-order-attack-move --units <tankIds> --x <x> --y <y>
```

直接攻击只接受当前可见敌方目标；attack-move 允许向地图坐标推进，但不能因此泄露隐藏敌军。执行层仍需校验 actor 是本局己方坦克。

### 5.3 停止

```text
rwx-game-order-stop --units <tankIds>
```

只允许本局己方坦克。停止命令不能作用于维修湾、敌方炮塔或其他实体。

## 6. 禁止行为与权限边界

- 建造、生产、升级、回收、拆除任何建筑/单位。
- 操控维修湾、敌方实体、非本局坦克或其他玩家实体。
- 任意 action、技能、姿态、自动化规则、编组自动管理或其他未列入白名单的命令。
- pause、step、speed、restart、surrender。
- 通过通用 `rwx-game-*`、底层 Command、Gateway 旁路或直接 Replay 入口绕过 Case 白名单。
- 读取隐藏敌军配置、meta 私有字段、EngineEvidence、其他 run 或结果目录。

必须在 CLI parser、Gateway、通用游戏服务、引擎 Command 提交和 ReplayWriter 录制边界共同校验。违规请求执行前拒绝，独立写 `violations.jsonl`，至少包含 violationId/requestId/actor/target/command/tick/gameMs/wallMs/type/reason/rejected=true。拒绝请求不得进入 Replay、不得改变游戏状态。

## 7. 错误

普通错误示例：`expected_units`、`invalid_unit_id`、`unit_not_owned`、`target_not_visible`、`invalid_coordinate`、`not_running`、`already_begun`。

违规示例：`build_not_allowed`、`production_not_allowed`、`non_case_actor`、`enemy_control_not_allowed`、`hidden_target_query`、`generic_orders_not_allowed`、`unauthorized_native_command`。

隐藏目标查询统一返回 `unknown_or_not_visible`，不得通过错误差异暴露隐藏敌人存在性。

