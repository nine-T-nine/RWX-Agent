# CASE3-Transport 参与说明

## 1. 启动

从仓库根目录启动。示例任务 `3_1_1`、`seed=1` 仅为语法示例；正式评测由评测方指定 task、seed 和端口文件。

```powershell
# Headless
.\gradlew.bat :desktop:run '--args=--headless --no-steam --evaluate CASE3-Transport --case-args task=3_1_1,seed=1,track=realtime --data-dir=agent-operation/tmp/transport-a --cli-port-file=agent-operation/tmp/transport-a/port'

# Window GUI
.\gradlew.bat :desktop:run '--args=--window --no-steam --evaluate CASE3-Transport --case-args task=3_1_1,seed=1,track=realtime --data-dir=agent-operation/tmp/transport-a --cli-port-file=agent-operation/tmp/transport-a/port'
```

等待就绪消息（实际格式以实现为准）：

```text
RWX_MICRO_READY case=CASE3-Transport phase=running tick=...
```

本 Case 从进入游戏起立即以 1× realtime 运行，并自动执行一次初始状态观察；没有 `begin`、暂停、步进或加速阶段。若评测方已经启动实例，直接使用其提供的端口文件，不要重复启动或硬编码端口。

## 2. 连接 CLI

```powershell
python agent-operation/tools/rwx-cli.py --port-file agent-operation/tmp/transport-a/port --stdio
```

整个对局保持同一个客户端和标准流连接：

- 等待 stderr 输出 `ready`。
- 每次向 stdin 写入一行命令并 flush。
- 每次从 stdout 读取一行 JSON。
- 外层 `reply` 为字符串；命令使用 `--json` 时，再解析其中的 JSON。
- CLI 不会替 Agent 固定轮询或等待生产/运输完成；观察和调度时机由 Agent 决定。

## 3. 任务与目标

任务 ID：`3_<type>_<level>`，type 为 1～4，level 为 1～3。level 是目标数量倍率 `a`。

| 类型 | 目标单位组成 | 给定运输单位 | 工厂 | 初始资金 | 游戏上限 |
|---|---|---:|---|---:|---:|
| 1 | 重型坦克 3a、等离子坦克 2a、自行火炮 3a | 运输机 ×3 | T2 陆军工厂 ×2 | 2,000 | 15 分钟 |
| 2 | 机枪机甲 a、火炮机甲 2a | 运输机 ×3 | T2 机甲工厂 ×2 | 4,000 | 20 分钟 |
| 3 | 喷火机甲 a、特斯拉机甲 a、猛犸坦克 2a、重型坦克 3a | 运输机 ×3 | T2 机甲工厂 ×2、T2 陆军工厂 ×2 | 4,000 | 25 分钟 |
| 4 | 实验型悬浮坦克 2a、实验型概念坦克 a | 飞行堡垒 ×2 | 实验工厂 ×2 | 20,000 | 30 分钟 |

目标组成在进入对局后通过 `rwx-micro-3-goal --json` 获取。不要在启动前假设本局目标。精确内部单位 ID、动作 ID、单位容量和生产时间以运行时公开数据为准。

## 4. 地图与任务区域

- 地图为 130×130 格，岩浆背景；左上角目标岛约 1375–1688 格草地（横向更宽、沿接近方向更短的椭圆），右下角基地岛约 700–800 格草地（按格数算）。
- 基地岛预置超频提取器和对应工厂；运输单位出生在基地岛边缘。
- 目标岛附近存在敌方防空火力：运输单位进入其射程会被攻击，卸载点和航线请结合实际侦察自行选择。
- 地图采用一次性迷雾。具体揭示语义以本局实际的 `map-show` 观察为准。
- 目标是让指定陆军单位实际到达左上角目标岛。仍在货舱、停留在基地岛、位于岩浆或已阵亡的单位不计入目标组成。

## 5. 推荐参与顺序

```text
rwx-micro-3-state --json
rwx-micro-3-goal --json
rwx-micro-3-production --json
rwx-micro-3-fleet --json
rwx-game-map-show
rwx-game-unit-list --owner self --full --json
```

随后按本局目标生产单位，使用 `rwx-micro-3-load` 装载、`rwx-micro-3-move` 移动运输单位、`rwx-micro-3-unload` 卸载。具体参数见 [command.md](command.md)。

运输容量按引擎真实**席位权重**计算：并非每个单位都占 1 席（部分单位占多席），以运输单位实际剩余容量为准。不得超过剩余容量。不能把“命令已受理”当作生产完成、装载完成、抵达或卸载完成。应以之后的状态观察为准。

## 6. 时间、胜负与限制

- 游戏时间上限：type 1/2/3/4 分别为 15/20/25/30 分钟。
- 墙钟预算 = 游戏上限 + 120 秒；剩余时间由 `state` 公布。
- 运输单位全部阵亡：立即失败。
- 目标岛上各类型存活陆军数量首次达到要求：成功。
- 时间耗尽：timeout；运行异常：error。
- 仅 realtime。不得暂停、步进、加速、重启或投降。
- 不得建造建筑、生产非目标单位、升级工厂、直接操控目标陆军移动，或使用通用命令绕过 Case 白名单。
- 违规在执行前拒绝并记录，不进入 Replay，不改变胜负；普通参数错误与行为违规分开统计。

## 7. 结束与评测数据

结算动画和退出行为沿用 CASE2 的实现约定：窗口模式显示结算动画；Headless 输出结果；两种模式均在结果落盘后按配置倒计时退出（建议 10 秒）。实际输出以 `RWX_MICRO_RESULT` 和 `result.json` 为准。

参赛期间不得读取源码、隐藏证据、其他 run 或 `RWX_MICRO_RUN` 指向的目录。所有评测数据由评测方在对局结束后离线分析。

