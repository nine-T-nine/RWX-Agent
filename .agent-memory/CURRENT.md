# CURRENT

更新时间：2026-10-27

## 当前快照

- 2026-10-27：实体查询与事件流已完成：`--owner any`、共享实时可见性、威胁能力分类与输出补全；事件流（NDJSON、聚合修订、断线续传、gap/overflow 诊断）与 JSONL 接收器完成，真实 Headless 对局验证通过（`HISTORY/task-016.md`）。
- 2026-10-27：`game-state` 合并宏观统计与共享事件，旧摘要命令已移除；Headless 房主 + Windows GUI 非空事件和历史保留验证通过（`HISTORY/task-015.md`）。
- 本地 Windows GUI 当前未运行；上次 `水田2P` 对 Very Hard AI 的录制对局已于 06:56 按用户指令投降结束。CLI/事件端口文件在 `agent-operation/tmp/`，失效时以 `rwx-ping` / `RWX_EVENTS_READY` 校验。
- 项目网站：https://nine-t-nine.github.io/RWX-Agent/ ，源码在 `web/`；`web/**` 通过 Actions 自动校验并部署 Pages，维护见 `web/README.md`。
- 已移除有问题的核弹录像展示、原片、网页副本及封面；现保留三场录像，生成配置和文档同步更新（`HISTORY/task-012.md`）。
- 独立公开仓库 `nine-T-nine/RWX-Agent` ID 为 `1434131753`，Contributors 已验证公开维护身份。
- 本地 `replays/` 最近核对为 10 个完整样本；19:59 的清理（22 → 10）非 Agent 工具所为。
- 回放反解析与命令记录工具在 `evaluate/utils/`；验证与复现见 `HISTORY/task-010.md`、`HISTORY/task-011.md`。

## 历史与远程

- 功能基线为 `20cf9b9 → a7bdfc3`，保留真正上游的全部原始提交；历史备份在仓库外，不应提交或发布。
- 4 个自有历史提交采用公开维护身份；4 个匿名提交和 72 个上游提交保持原样。
- `origin`/`myfork` 继续跟踪上游；个人开发仅推送 `publish`（`nine-T-nine/RWX-Agent`）。

## 验证基线

- 2026-10-27：完整 `:core:test :desktop:headlessArchive` 26 套件、224 项、零失败；真实 Headless 对局验证事件 JSONL 连续、续传不重复（`HISTORY/task-016.md`）。
- 2026-10-26：完整 `:core:test` 24 套件、206 项、零失败；Headless 发行包构建通过。
- 命令记录器：真实 Headless 房主 + 两个 Windows CLI 客户端连续两局通过，39 条命令及分类统计一致。
- 网站：三场录像、13 个发布文件、117.2 MiB；本地链接、视频 SHA-256、JS 语法及清单一致性通过；`b4effcd` 已推送 `publish/main`，Pages 部署成功（运行 `40323714015`）。

## 下一步

- 按 `web/README.md` 从 `origin/main` 同步，在本地处理冲突后发布至 `publish/main`。
- 原有遗留：从游戏内菜单手动输入地址加入服务器尚未实测。

## 媒体

- 三个保留原片位于 `web/resource/` 并忽略入库；网页副本位于 `web/resource/matches/`，完整站点约 117.2 MiB。
