# CURRENT

更新时间：2026-10-26

## 当前快照

- 项目网站已发布：https://nine-t-nine.github.io/RWX-Agent/ 。源码在 `web/`，公开仓库为 `nine-T-nine/RWX-Agent`。
- `web/**` 更新由 GitHub Actions 自动校验并部署 Pages；维护步骤见 `web/README.md`。

## 历史与远程

- 功能基线为 `1c2e85b → 21c2114`，保留上游祖先；旧历史备份在仓库外，不应提交或发布。
- `origin`/`myfork` 继续跟踪上游；个人开发仅推送 `publish`（`nine-T-nine/RWX-Agent`）。

## 验证基线

- 2026-10-26：完整 `:core:test` 23 个套件、200 项全通过；本轮不改引擎。
- 网站：四段视频本地完整解码、320/390/768/1440px 无横向溢出、菜单/平台切换通过；公开站点四个 MP4 均返回 206，线上浏览器实际播放通过。
- 首次 Pages 部署成功：Actions run `40271754450`。

## 下一步

- 按 `web/README.md` 从 `origin/main` 同步，在本地处理冲突后发布至 `publish/main`。
- 原有遗留：从游戏内菜单手动输入地址加入服务器尚未实测。

## 媒体

- 原片保留于 `web/resource/` 并忽略入库；网页副本位于 `web/resource/matches/`，完整站点约 163.5 MiB。
