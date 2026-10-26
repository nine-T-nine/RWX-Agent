# task-006（2026-10-26）

## 目标

在 `web/` 实现 RWX-Agent 项目网站，引用四场经典录像，发布完整项目到独立的 `nine-T-nine/RWX-Agent` 并部署 GitHub Pages。

## 改动

- 静态 HTML/CSS/JS：游戏介绍、当前项目能力、四场录像播放器、平台启动命令、上游来源与定期同步声明。
- 保留原始录像，生成完整时长的 1080p/60fps H.264/AAC faststart 网页副本、实机 WebP 封面和 SHA-256 清单；原片不进入 Git。
- 加入手机导航、键盘关闭、录像切换、复制及选中回退；支持无脚本视频直链和减少动画设置。
- 新增 Pages 工作流，仅发布网页文件和播放资源；现有游戏 CI 忽略纯网站/文档变更。
- 创建独立公开仓库并配置 `publish`，提交使用用户指定身份；未向 `origin` 或 `myfork` 推送。
- 根 README 和 `web/README.md` 提供来源、运行、媒体生成、验证、Pages 发布及上游同步步骤。

## 验证

- `python web/tools/check_site.py`：4 段录像、15 个部署文件、163.5 MiB，资源/锚点/大小/哈希通过；`node --check web/app.js` 通过。
- 本地浏览器：四场录像实际解码播放，均为 1920×1080 且时长正确；平台切换、复制回退、手机菜单与 Escape 关闭通过，无脚本异常。
- 320/390/768/1440px 按 `scrollWidth <= clientWidth` 验证无横向溢出；修复手机装饰背景越界。
- 完整 `:core:test`：23 套件、200 测试、0 失败、0 错误、0 跳过；使用隔离构建目录，未改引擎代码。
- Pages run [40271754450](https://github.com/nine-T-nine/RWX-Agent/actions/runs/40271754450) 成功，部署源码提交 `a78e56f`。
- 公网 HTML/CSS/JS 与提交内容一致，四个 MP4 均支持 HTTP 206 且大小正确；公开网页浏览器实测播放成功，手机布局及脚本错误检查通过。

## 交付与维护

- 网站：https://nine-t-nine.github.io/RWX-Agent/
- 仓库：https://github.com/nine-T-nine/RWX-Agent
- 网站说明：[web/README.md](../../web/README.md)。站点不直接连接游戏或模型服务；后续改页面推送 `publish/main` 自动部署。
