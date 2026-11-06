# RWX-Agent 项目网站

介绍项目能力并播放四场实机对局的静态网站，外加一页评测结果；无前端依赖或构建步骤。

- 网站：https://nine-t-nine.github.io/RWX-Agent/
- 评测结果：https://nine-t-nine.github.io/RWX-Agent/results.html
- 本项目源码：https://github.com/nine-T-nine/RWX-Agent
- RWX 上游：https://github.com/yomi2539/RWX

## 本地预览

从项目根目录执行，需要 Python 3：

```powershell
python -m http.server 4173 --bind 127.0.0.1 --directory web
```

打开 http://127.0.0.1:4173/ 。使用 HTTP 服务预览，以便 JavaScript 读取录像清单与评测数据。

## 修改页面

| 文件 | 内容 |
| --- | --- |
| `index.html` | 中文首页内容、导航、无脚本视频链接 |
| `results.html` | 评测结果页：`old_v1.1` 归档总表（时长 / 胜负 / Agent↔工具）、四项 Stage3 扣分矩阵、`total-v2` 评分规则、微操 CASE、未进入排名的材料与口径限制 |
| `styles.css` | 桌面、平板、手机布局与减少动画适配 |
| `app.js` | 首页录像切换、平台切换、复制命令和手机导航 |
| `results.js` | 读取 `resource/results.json` 与 `resource/scoring.json` 渲染总表、扣分矩阵、评分规则与材料缺口 |
| `resource/matches.json` | 四场录像的名称、说明、时长及 SHA-256 |
| `resource/results.json` | schema 2：`old_v1.1` 全部可评估对局的 `total-v2`、模型 + 工具 + 推理强度、时长、胜负、四项 Stage3 扣分与忽略原因 |
| `resource/scoring.json` | 完整对局（Stage1-v2 / Stage2 / Stage3 四项）与四个微操 CASE 的扣分项、上限、公式、说明和环境图索引 |
| `resource/cases/` | 四个微操 CASE 的固定 task + seed 真实窗口截图（WebP） |
| `resource/matches/` | 已提交的 H.264/AAC 网页录像，完整时长、1080p/60fps |
| `resource/posters/` | 从原录像提取的 WebP 封面 |

本页没有接入游戏实例或云端模型 API。页面中的命令用于在访问者本机启动游戏；录像是真实录制的对局，不是在线游戏画面。

## 更新评测结果

评测结果来自仓库的评分产物（`evaluate/output/<对局>/evaluation_v2.json`、`score_v2.json`、`stage_3/score.json`，都不纳入版本库）。更新流程：

1. 按 `evaluate/README.md` 跑完 v2 评分（或直接 `node evaluate/tools/evaluate_corpus_v2.js` 整批跑），确认阶段版本为 `stage1-score-v2` / `stage3-score-v2`。
2. 跑 `node evaluate/tools/build_web_results_v2.js`：它读 `evaluate/output/_v2-summary.json` 与各局 `meta.json`，写 `resource/results.json`（页面不做二次换算，`total-v2` 一致性由 `check_site.py` 校验）。模型、工具、推理强度、会话这些身份信息以对局目录 `meta.json` 为准（`evaluate/tools/match_materials.js` 生成并维护），多 Agent 局按 `pairs` 一对一列出，工具改造写在 `extra`。
3. 归档语料缺结构化 `meta.json` 的，在 `build_web_results_v2.js` 顶部的 `ARCHIVE` 表补日期与参与模型；材料不足或重放分叉的局由脚本写进 `ignored`，页面单列"未进入排名"，不要手工补分。
4. Stage3 四项的上限/公式由 `resource/scoring.json` 提供：`node web/tools/build_scoring_v2.js` 从评分产物与 `evaluate/config.json` 重新生成它。
5. 运行 `python web/tools/check_site.py`：校验页面链接、录像校验和、每条记录的 `total-v2` 与两阶段加权、四项 Stage3 是否齐全、`pairs` 字段与 `ignored` 结构。

`check_site.py` 不读取 `evaluate/`（该目录不进版本库），因此分数一致性以 `results.json` 内部为准。

## 重新生成媒体

四个原始 MP4 保留在 `resource/`，不进入 Git；其中一个超过 GitHub 单文件限制。网站使用 `resource/matches/` 内的压缩副本，原始视频不被覆盖。

准备 FFmpeg（同时提供 `ffprobe`），将原始文件放回 `resource/`，然后从项目根目录运行：

```powershell
python web/tools/prepare_media.py
python web/tools/check_site.py
node --check web/app.js
```

生成器复用已存在的网页 MP4；需要重新编码某段录像时，先删除对应的 `resource/matches/<id>.mp4`，再运行生成器。生成器会刷新封面和清单。更改标题、说明时同步修改生成器内的记录定义与清单。

网页录像采用 H.264、AAC、`yuv420p` 和 MP4 faststart，优先保证浏览器兼容和快速起播。校验器检查本地链接、锚点、视频哈希、四段录像的完整性及站点体积。

## 发布 GitHub Pages

仓库 Pages 的构建来源使用 **GitHub Actions**。工作流见 [pages.yml](../.github/workflows/pages.yml)：

1. `main` 上的 `web/**` 或 Pages 工作流更新时自动运行，也支持手动触发。
2. 校验资源后，仅复制网页与播放资源至 `_site/`，不发布原片或开发工具。
3. 上传 Pages artifact，再部署至 GitHub Pages。

本地已配置发布远程时，可执行：

```powershell
git push publish main
gh workflow run pages.yml --repo nine-T-nine/RWX-Agent --ref main
```

没有页面更新时不必手动触发；前一次 Pages 工作流失败时，可查看 Actions 日志后重跑。

## 同步 RWX 源码

本项目基于 RWX 扩展修改，并定期同步上游。`origin` 用于同步原始源码，`myfork` 保持上游跟踪，个人开发仅发布到独立的 `nine-T-nine/RWX-Agent`：

以上是维护者工作区的远程命名。首次从本公开仓库克隆时，`origin` 默认指向 RWX-Agent；如需采用相同约定，先执行以下配置（已配置的维护者工作区无需重复执行）：

```powershell
git remote rename origin publish
git remote add origin https://github.com/yomi2539/RWX
```

之后同步上游并发布：

```powershell
git switch main
git fetch origin
git merge origin/main
git push publish main
```

存在合并冲突时，先在本地解决、检查并提交，再发布；不将个人扩展推送到 `origin` 或 `myfork`。
