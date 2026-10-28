# CURRENT

更新时间：2026-10-27

## 当前快照

- 2026-10-28：网站新增 Claude 利用暂停对战 Very Hard AI 录像，清理清单中已不存在的旧录像；按用户要求未编译验证（`HISTORY/task-025.md`）。
- 2026-10-28：事件订阅按复盘需要重做三处：`new` 合并窗口 3s→5s（跨帧修订与 CLI 分组同一窗口，`losses` 仍 3s）；接收器把 `update` 按类型拆成两个各 500 条的日志（`game-events-observe.jsonl` = new/info，`game-events-combat.jsonl` = damaged/destroyed/losses）；默认订阅仍 `info=false, allies=false`，但 `new`/`info` 不再受 `allies` 收窄。同时修掉 `compact()` 在 `os.replace` 失败后句柄一直关闭、整局静默停写的问题（`HISTORY/task-024.md`）。
- 2026-10-27：事件 JSONL 接收器加接收侧二次过滤，只落盘 `kind == "update"`；`hello`/`event`/`remove`/`cursor`/诊断不再进文件，诊断只走 stderr；续传游标仍跟住所有序列号。真实抓包 500 条回放验证存 181 条全为 `update`、重连 `after=930` 不重复（`HISTORY/task-023.md`）。
- 2026-10-27：CLI `--stdio`、通信文档和基准工具完成；完整 26 套件 226 项通过，真实 Headless 暂停对局的三种通信方式及文档示例已验证，结果见 `HISTORY/task-021.md`。
- 2026-10-27：清理 `agent-operation/tools`：删除旧版文件邮箱 `rwx-cli-daemon.ps1`、`__pycache__`、空 `tmp/`，并同步 README 与两个 ps1 的引用；`experimental_scripts/` 按用户要求保留（`HISTORY/task-020.md`）。
- 2026-10-27：英文 `help.md` 按重写后的 `help_zh.md` 全文同步（结构改为加粗小标题、参数子列表、两列优先级表；43 个标题/命令/示例逐条对齐），`rwx-help` 打包内容同步更新（`HISTORY/task-019.md`）。
- 2026-10-27：事件 JSONL 接收器文档补全「游戏启动后另开的本机事件 Socket → tmp JSONL」链路，并把日志改为滚动保留最新 500 条（`HISTORY/task-018.md`）。
- 2026-10-27：命令记录文件改为 `commands-<地图名>-<日期>--<唯一编号>.txt`（统计同名 `-stats.txt`）：启动时先用 `commands-lobby-…` 占位，首个有效对局改名并保留原随机编号；无地图不消耗改名，后续对局沿用首局地图名（`HISTORY/task-017.md`）。
- 2026-10-27：实体查询与事件流已完成：`--owner any`、共享实时可见性、威胁能力分类与输出补全；事件流（NDJSON、聚合修订、断线续传、gap/overflow 诊断）与 JSONL 接收器完成，真实 Headless 对局验证通过（`HISTORY/task-016.md`）。
- 2026-10-27：`game-state` 合并宏观统计与共享事件，旧摘要命令已移除；Headless 房主 + Windows GUI 非空事件和历史保留验证通过（`HISTORY/task-015.md`）。
- 2026-10-27：macOS Headless 2v2 录制对局（4P绿洲湖畔byConfetti，Very Hard x3，income x2）我方约 21:20 战败，盟友 AI 仍在；命令记录导出 `observe4.txt`，回放在 `replays/`。游戏进程仍在运行（录制中）。CLI/事件端口文件在 `agent-operation/tmp/`，失效时以 `rwx-ping` / `RWX_EVENTS_READY` 校验。
- 项目网站：https://nine-t-nine.github.io/RWX-Agent/ ，源码在 `web/`；`web/**` 通过 Actions 自动校验并部署 Pages，维护见 `web/README.md`。
- 已移除有问题的核弹录像展示、原片、网页副本及封面（`HISTORY/task-012.md`）。
- 独立公开仓库 `nine-T-nine/RWX-Agent` ID 为 `1434131753`，Contributors 已验证公开维护身份。
- 本地 `replays/` 最近核对为 10 个完整样本；19:59 的清理（22 → 10）非 Agent 工具所为。
- 回放反解析与命令记录工具在 `evaluate/utils/`；验证与复现见 `HISTORY/task-010.md`、`HISTORY/task-011.md`。

## 历史与远程

- 功能基线为 `20cf9b9 → a7bdfc3`，保留真正上游的全部原始提交；历史备份在仓库外，不应提交或发布。
- 4 个自有历史提交采用公开维护身份；4 个匿名提交和 72 个上游提交保持原样。
- `origin`/`myfork` 继续跟踪上游；个人开发仅推送 `publish`（`nine-T-nine/RWX-Agent`）。

## 验证基线

- 2026-10-28：完整 `:core:test` 26 套件、228 项、零失败；事件接收器 Python 单测 14 项通过；双日志分流与 `compact()` 失败自愈的端到端用例通过（`HISTORY/task-024.md`）。
- 2026-10-27：接收器二次过滤用真实抓包（500 条：181 `update`/237 `cursor`/56 `event`/26 `remove`）回放验证：落盘 181 条且全为 `update`、序列与抓包一致；复用同一输出文件二次连接时 `after=930`、无重复（`agent-tmp` 临时脚本，验证后已删）。
- 2026-10-27：完整 `:core:test` 26 套件、226 项、零失败（`help.md` 同步后复跑，`RwxHelpSectionsTest` 通过）。
- 2026-10-27：完整 `:core:test :desktop:headlessArchive` 26 套件、224 项、零失败；真实 Headless 对局验证事件 JSONL 连续、续传不重复（`HISTORY/task-016.md`）。
- 2026-10-26：完整 `:core:test` 24 套件、206 项、零失败；Headless 发行包构建通过。
- 命令记录器：真实 Headless 房主 + 两个 Windows CLI 客户端连续两局通过，39 条命令及分类统计一致。
- 网站历史验证：`b4effcd` 时本地链接、视频 SHA-256、JS 语法及清单一致性通过，Pages 部署成功（运行 `40323714015`）；新增录像后按要求未验证。

## 下一步

- 按 `web/README.md` 从 `origin/main` 同步，在本地处理冲突后发布至 `publish/main`。
- 原有遗留：从游戏内菜单手动输入地址加入服务器尚未实测。

## 媒体

- 三个原片位于 `web/resource/` 并忽略入库；网页副本位于 `web/resource/matches/`。
