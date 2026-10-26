# task-008（2026-10-26）

## 目标

删除并同名重建独立公开仓库与 GitHub Pages，发布已清理身份的 main，保留原有上游和匿名作者。

## 改动

- 完成 GitHub 官方 `delete_repo` 补充授权，仅操作已核实所有者的 `nine-T-nine/RWX-Agent`。
- 先在仓库外导出仅含干净 main 的自包含 bundle，完成 `git bundle verify`、Git pack 完整校验和、SHA-256 和完整 fsck 检查。
- 删除旧 Pages 和仓库，再创建同名独立公开仓库；新仓库 ID 为 `1434131753`，已核实与旧对象不同。
- 原样推送 main，重新创建 workflow 类型 Pages 并运行部署；不推送或改动 `origin`/`myfork`，不增加多余远程配置。
- 首次发布使用 skip-ci 提交避免触发无关游戏构建，Pages 独立手动触发，后续网站修改仍自动部署。

## 验证

- 新仓库 Contributors：公开维护账号 `nine-T-nine` 已出现，其余具名贡献者仍为 `yomi2539` 与 `GuraQwQ`。
- 4 个旧身份提交无法从新仓库查询，旧仓库 ID 返回不存在；真正上游的 72 个提交 ID 保持原样，4 个匿名提交的身份保持原样。
- [Pages run 40296530453](https://github.com/nine-T-nine/RWX-Agent/actions/runs/40296530453) 成功，部署源码提交 `43c27cf`。
- 公开 HTML/CSS/JS 与本地源码一致，录像清单一致；四个 MP4 均返回 HTTP 206，媒体类型、文件头与长度正确。
- 本地网站校验：4 段录像、15 个发布文件、163.5 MiB；静态链接、资源哈希和 JS 语法通过。引擎源码未改，沿用上一轮 200 项单测基线。
- 重建前已有的 `mods/maps` 未提交删除保持原状，未将其纳入任何提交或推送。

## 交付

- 仓库：https://github.com/nine-T-nine/RWX-Agent
- 网站：https://nine-t-nine.github.io/RWX-Agent/
- 网站源码及维护说明：[web/README.md](../../web/README.md)。仓库外备份及远程操作记录未公开上传。
