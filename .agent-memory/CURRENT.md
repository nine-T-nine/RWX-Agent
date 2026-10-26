# CURRENT

更新时间：2026-10-26

## 当前快照

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

- 2026-10-26：完整 `:core:test` 24 套件、206 项、零失败（本次重跑通过）；Headless 发行包此前构建通过。
- 命令记录器：真实 Headless 房主 + 两个 Windows CLI 客户端连续两局通过，39 条命令及分类统计一致。
- 网站：三场录像、13 个发布文件、117.2 MiB；本地链接、视频 SHA-256、JS 语法、生成配置与清单一致性校验通过。

## 进行中

- 完整单测与网站校验已通过，测试临时产物已清理；正在提交并推送 `publish/main`，随后确认 Pages 部署。

## 下一步

- 按 `web/README.md` 从 `origin/main` 同步，在本地处理冲突后发布至 `publish/main`。
- 原有遗留：从游戏内菜单手动输入地址加入服务器尚未实测。

## 媒体

- 三个保留原片位于 `web/resource/` 并忽略入库；网页副本位于 `web/resource/matches/`，完整站点约 117.2 MiB。
