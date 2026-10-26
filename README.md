# RWX-Agent：让智能体参与经典RTS游戏Rusted Warfare

[项目网站与经典对局](https://nine-t-nine.github.io/RWX-Agent/) · [本项目 GitHub](https://github.com/nine-T-nine/RWX-Agent) · [RWX 官方仓库](https://github.com/yomi2539/RWX)

基于 [RWX](https://github.com/yomi2539/RWX) 的实时战略游戏项目。本仓库重点整理和扩展 `core/src/main/java/com/corrodinggames/rts/game/ai/custom` 下的自定义 AI 与本地命令工具。原项目 README 已备份为 [README.original.md](README.original.md)。

RWX-Agent 基于 RWX 仓库扩展修改，并定期同步上游源码。网站源码与四场实机对局位于 [`web/`](web/)，维护和发布步骤见 [`web/README.md`](web/README.md)。

## 让Agent参战

把`agent-operation/START.md`喂给AI

## 运行

准备 JDK 25，在项目根目录执行：

```powershell
.\gradlew.bat :desktop:run
```

需要在 Windows 上同时使用游戏命令终端时，执行：

```powershell
.\gradlew.bat :desktop:rwxCliRun
```

## 增加自定义 AI

自定义 AI 源码在 [`core/src/main/java/com/corrodinggames/rts/game/ai/custom`](core/src/main/java/com/corrodinggames/rts/game/ai/custom)。启动游戏后，在本地战役室添加 AI 玩家，并从 AI 难度列表选择已注册的自定义 AI，例如 `AI-high` 或 `AI_S`。这些选项由 [`CustomAiCatalog.java`](core/src/main/java/com/corrodinggames/rts/game/ai/custom/CustomAiCatalog.java) 统一注册。

> 新增一个 AI：

1. 参考 [`high/CustomHighAiStrategy.java`](core/src/main/java/com/corrodinggames/rts/game/ai/custom/high/CustomHighAiStrategy.java)，在 `custom` 下新建实现 `AiStrategy` 的类。
2. 在类的 `definition()` 中设置唯一且稳定的编号（`>= 4`）、ID、显示名称和策略工厂；按需设置收入倍率及保留的原版 AI 阶段。
3. 将该类的 `definition()` 加到 `CustomAiCatalog.definitions()`，重新启动游戏后即可在 AI 列表中选择。已用于存档的编号和 ID 不要更改。

`util` 目录提供策略辅助代码；[`analysis.md`](core/src/main/java/com/corrodinggames/rts/game/ai/custom/analysis.md) 说明原版 AI 与自定义层的接入方式。

> 理解游戏AI机制

参见'core/src/main/java/com/corrodinggames/rts/game/ai/custom/analysis.md`.

## 使用本地命令终端

运行 `:desktop:rwxCliRun` 后，Windows 会打开 **RWX Command Terminal**。在该终端中输入：

```powershell
rwx-help
rwx-skirmish
rwx-battleroom-add-ai --difficulty AI_S
rwx-battleroom-start
rwx-game-state --summary
```

`rwx_cli_tool` 的完整命令、参数和限制见 [`help.md`](agent-operation/help.md)。

## 许可与来源

项目许可证见 [LICENSE](LICENSE)。原项目说明见 [README.original.md](README.original.md)。
