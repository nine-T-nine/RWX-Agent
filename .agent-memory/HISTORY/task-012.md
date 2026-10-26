# task-012（2026-10-26）

## 目标

按用户要求下架录制有问题的 `gpt6.1solvsVeryHard-核弹.mp4`，删除视频文件，并发布到远程仓库。

## 改动

- 删除本地忽略入库的原始 MP4，以及已跟踪的网页副本 `web/resource/matches/nuclear.mp4` 和封面 `web/resource/posters/nuclear.webp`。
- 移除页面播放按钮、无脚本观看链接、录像清单与媒体生成配置中的核弹录像，避免后续生成时恢复。
- 页面数量改为三场，保留录像重新编号；同步资源校验器、根目录与网站 README、项目记忆。

## 验证

- `rtk proxy python -X utf8 web/tools/check_site.py`：三场录像、13 个发布文件、117.2 MiB；本地链接、视频大小及 SHA-256 全部通过。
- `rtk proxy node --check web/app.js` 与 `rtk git diff --check` 通过。
- 核对媒体生成定义与清单一致，网页目录只剩三个播放 MP4；原片、副本、封面均已删除，`web/` 文本资源无核弹录像残留引用。
- 完整 `:core:test` 隔离构建通过：24 个套件、206 项、0 失败、0 错误、0 跳过；使用 `PROJECT.md` 的隔离参数（本次仅执行 `:core:test`）。
- 本次创建的隔离构建目录、Gradle 项目缓存和初始化脚本已清理。

## 发布

使用项目约定的 `rtk git push publish main` 发布到 `nine-T-nine/RWX-Agent`；`web/**` 更新自动触发 Pages 校验与部署。

## 遗留

待推送并确认 Pages 自动部署；工作期间出现的独立 `.gitignore` 修改未纳入本次提交。
