# 内部启动开关

维护者自用，不写进 Agent/评测者文档。全部由 `AppOptions.parseArgs` 解析，互斥关系在解析末尾统一 `require`。

| 开关 | 作用 |
|---|---|
| `--sandbox-map=<x.tmx>` | 启动后进**沙盒战役大厅**并选中该图；`.tmx`，相对/绝对路径；点“开始游戏”才载入 |
| `--screen=<id>` | 指定启动界面：`main-menu` `single-player` `level-select` `replay-select` `settings` `multiplayer` `mods` `resource-browser` `battleroom` `loading` `paused` `in-game` |
| `--level-mode=<id>` | 关卡列表分类：`campaign` `skirmish` `challenge` `survival` `custom` |
| `--auto-replay=<name>` | 启动即回放 |
| `--join-server=<host:5123>` | 启动即连原版服务器 |
| `--auto-start-singleplayer-ui` / `--auto-start-battleroom[-twice]` | 自动走到开局（冒烟、截图） |
| `--auto-return-main-menu-after-game-ready` | 开局就绪后回主菜单 |
| `--evaluate[=case\|tier]`、`--case-args`、`--micro-config` | 打开证据采集；按 micro CASE 跑 |
| `--tier=<1..3>`、`--alert-delivery=<l0\|l1\|l1-lite>`、`--speed-profile=<realtime\|slow:0.5\|2>` | 评测校准 |
| `--rwx-cli`、`--cli-port-file=<path>` | 开 CLI 端口并写端口文件 |
| `--data-dir=<dir>` | 换存档/配置根目录（多实例隔离） |
| `--render-backend=<slick\|skia\|headless>` | 选渲染后端；`headless` 等同 `--headless` |
| `--window` | 显式要求窗口（脚本/日志标记） |
| `--demo-dialog`、`--color-scheme=<id>`、`--settings-page=<id>` | UI 调试 |

JVM 属性：`-Dlaunch.dir`（= `--data-dir`）、`-Drwx.cli.portFile`、`-Drwx.evaluate.dir`、`-Drwx.tier`。

边界：
- `--sandbox-map` 要等主菜单出现才准备房间（会话必须先启动），且与 `--headless`、`--auto-replay`、`--join-server`、micro 互斥。
- 官方批量无头（`--headless --map=… --ticks=…`）不在本文范围，说明只在 `.agent-memory/UPSTREAM.md`。
