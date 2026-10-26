# RWX 项目协作说明

本仓库是 Rusted Warfare 的桌面移植/自动化改造版（Java + Kotlin + Gradle）。任何 Agent 接手前先读
[.agent-memory/PROJECT.md](.agent-memory/PROJECT.md)、[.agent-memory/CURRENT.md](.agent-memory/CURRENT.md)、
[.agent-memory/DECISIONS.md](.agent-memory/DECISIONS.md)。

## 项目记忆（必须维护）

目录约定：

```
.agent-memory/
├── PROJECT.md      项目结构、构建/测试/联机流程（变动时更新）
├── CURRENT.md      当前进度快照：已完成 / 进行中 / 下一步 / 已知问题（每次工作结束前更新）
├── DECISIONS.md    决策记录：结论 + 理由 + 影响面（做出非显然选择时追加）
└── HISTORY/
    └── task-NNN.md 单项任务收尾记录：目标、改动、验证、遗留
```

规则：

- 开工先读 `CURRENT.md`，不要凭记忆猜进度。
- 状态变化（完成一项验证、发现新阻塞、改了下一条计划）就更新 `CURRENT.md`，保持它不超过一屏。
- 架构选择、协议/权限取舍、放弃的方案写进 `DECISIONS.md`，一行一条，带日期。
- 任务完成或被放弃时写 `HISTORY/task-NNN.md`（下一个编号），并从 `CURRENT.md` 移除已结束条目。
- 记忆文件只写结论与可复现步骤，不粘贴大段日志。

## 临时文件

- 临时脚本、日志、截图一律放 `agent-tmp/`（已 gitignore），联机实例的端口文件放 `agent-operation/tmp/`（已 gitignore）。
- 任务结束或阶段验证完成后立刻删除自己的临时文件；仓库根目录不允许出现 `_*.txt`、调试脚本等残留。
- 长期有用的验证脚本要转正到 `agent-operation/tools/` 或 `agent-operation/server/` 并写进文档，不要留在 `agent-tmp/`。

## 构建与测试

```powershell
# 完整单测 + Headless 发行包（避免与 IDE 的 Gradle 输出打架）
.\gradlew.bat :core:test :desktop:headlessArchive --init-script .\agent-tmp\priority-isolated.gradle `
  --project-cache-dir .\agent-tmp\priority-gradle-cache --max-workers=1 --no-build-cache `
  --no-configuration-cache '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

测试结果 XML 在 `agent-tmp/priority-build/RWX_core/test-results/test/`。基线：23 个套件、200 项全通过（2026-10-26）。

## 运行与联机

- 启动、CLI 命令、端口文件、Headless、`rwx-connect`、托管房间：见 [agent-operation/START.md](agent-operation/START.md) 与 [agent-operation/help.md](agent-operation/help.md)。
- CLI 端口写入 `agent-operation/tmp/port`，工具脚本优先读它，不要硬编码端口。
- 云端部署模板在 `agent-operation/server/`，部署记录写 `agent-operation/DEPLOYMENT.md`。

## 代码约定

- 这是反编译还原的引擎：Java 侧保留混淆字段名（`bH`、`bG`、`D` 等），只为必要逻辑加注释，不重命名、不重排格式。
- 网络协议、房间权限、锁步时序尽量复用原版；改动前先确认原版语义，改动后在 `DECISIONS.md` 写理由。
- 新增 CLI 命令走 `RwxCliCommands` + `help.md` 同步；不保留旧别名。
- 改完必须跑完整 `:core:test`，涉及界面/联机再跑真实多进程验证（Headless 房主 + Windows GUI/CLI 客户端）。
