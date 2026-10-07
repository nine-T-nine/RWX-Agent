# 阶段一：本机单机语义化控制

警告：本阶段只支持本机 RWX 的单机战斗。联机、回放、存档修改和服务端连接不在范围内。

## 已交付能力

启动客户端时传入 `--agent-port=7878`，RWX 会仅在 `127.0.0.1:7878` 开启控制端口。端口连接到游戏循环，而非 Windows 鼠标或键盘：状态在模拟线程发布，单位命令也在模拟线程经 `CommandController` 排队执行。

```powershell
Set-Location X:\game\RWX
.\gradlew.bat :desktop:run --args="--agent-port=7878"
python .\.agents\skills\rwx-control\scripts\rwxctl.py brief
python .\.agents\skills\rwx-control\scripts\rwxctl.py units own
```

当前支持读取本方/可见敌方单位，并对本方单位下达移动、攻击移动、攻击目标和停止命令。单位 id、坐标和目标 id 都由 CLI 返回，坐标为地图世界坐标。

## 典型循环

```powershell
$rwx = 'python .\.agents\skills\rwx-control\scripts\rwxctl.py'
& $rwx brief
& $rwx units own
& $rwx units enemy
& $rwx attack-move 42,43 900 650
& $rwx wait 2
```

`wait` 在本地等待指定秒数后自动读取最新 `brief`。`units enemy` 仅返回当前对本地玩家可见的敌方单位，不导出战争迷雾后的信息。

## 实现证据

- `LocalAgentRuntime` 在每个游戏 tick 发布快照，并在 `CommandController.executeAllCommands()` 前应用等待中的命令。
- `AgentControlServer` 仅解析本地文本协议，不直接触碰 UI 输入。
- 已通过 `:core:compileJava :desktop:compileKotlin`。

## 运行时验收（2026-09-30）

在本机单机遭遇战 `Valley Pass (6p)` 中验证通过。端点读取到队伍 0、指挥中心 `287` 和工程师 `288`；向工程师提交 `move 288 420 290` 后，2 秒内其位置从 `(330, 290)` 更新为约 `(417.07, 290.00)`。随后 `attack-move 288 420 290` 与 `stop 288` 均由模拟线程返回 `ok`。这验证了 CLI、回环端口、模拟线程队列、`CommandController` 以及状态发布闭环。

## 下一阶段

补充生产、建造、维修、巡逻、资源点和事件等待；保持同一协议和单机约束。网络客户端连接会在单机控制成熟后单独设计。
