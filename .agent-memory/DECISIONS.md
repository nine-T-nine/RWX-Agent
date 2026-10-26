# DECISIONS

按日期追加，一条一句结论 + 简短理由。

## 2026-10-26

- 网站使用无依赖 HTML/CSS/JS，源码位于 `web/`，由 GitHub Actions 部署 GitHub Pages；静态网站不直接连接游戏或模型服务。
- 保留四个原始录像并忽略入库，生成完整时长的 1080p/60fps H.264/AAC faststart 副本供网页播放，避免 GitHub 单文件大小限制；提交清单和哈希以便校验。
- `nine-T-nine/RWX-Agent` 是个人开发发布仓库；`origin`/`myfork` 保持原有上游用途，发布单独使用 `publish`，不向原远程推送个人扩展。

- 项目文档中的环境地址改为通用占位符；翻译致谢保留原有署名（按用户后续要求回退该部分改动）。

## 2026-10-24

- 复用原版多人协议，不自建同步：原版是房主调度的确定性锁步，各客户端跑完整模拟；云端只做“常驻房主”。
  重构网络同步（服务器权威、客户端仅显示）超出范围，已向用户说明。
- 不保留 `rwx-local-*` 旧命令别名：文档与实际接口必须一致，测试改为拒绝旧名。
- Headless 用同一引擎、替换窗口/绘制/音频适配，不用隐藏窗口冒充：需要真无 DISPLAY/Wayland 也能跑。
- CLI 端口写入 `agent-operation/tmp/port`（主文件 + 每实例记录、原子写、退出清理）：多实例不能互相覆盖，
  Agent 不应该硬编码端口。
- 投降改走引擎系统命令（`-surrender` 进入回放命令队列），不发送聊天文本：聊天文本不改变对局结果。
- 手动命令优先级高于所有微操规则，重新执行 `auto-set` 才恢复授权：用户反馈 guard 会把 attack-move 单位反复拉回。

## 2026-10-25

- 托管房间复用原版代理房主（`isProxyController`）+ 聊天命令通道，新增 `RwxManagedRoom` 策略模块，不改网络协议：
  原版已有代理房主权限位、`-start/-map/-addai/...` 命令、局中加入和返回大厅的数据包。
- 房主选举只在大厅进行，对局中冻结：用户明确要求“对局中始终不会移交权限”。
- 服务器托管时自己占旁观席，且允许局中加入（`allowJoinInProgress`）：新身份加入自动观战，原玩家重连保留原席位。
- “只剩 AI 或无人”判定为“没有在线非旁观玩家”：服务器旁观席和观战客户端不计入对局玩家。
- 第二局就绪问题的最终修法：客户端每局在 `gameHasBeenStarted` 后补发一次 packet 112（loading=true），回大厅时重新武装。
  依据：服务器 `a(false,0)` 只在所有连接 `connection.D==true` 时才继续，而 D 来自 112 的第二个布尔；
  原版每连接只发一次，且 `returnToBattleroom` 会清 D，于是第二局只能等 15 秒兜底（“Still waiting on: X”）。
  先前“按 isLoading 变化重发”的版本不可靠：地图加载可能在同一 tick 内完成，观察不到 true。
- CLI 选出生点要同时受地图容量限制（`MapMetadata.getNumberOfPlayersInMap` 解析 `[p3]`）：房间 `maxPlayers` 默认可到 10，
  把玩家放到地图没有的出生点会让该阵营开局 0 单位、立刻被判定全灭（实测“had no starting units (Team: B)”）。
- 中途加入观战用“开局时的玩家名字快照”判断，而不是注册回调：客户端在接管已开局的对局时会以新连接重注册，
  只按“新连接/previousName==null”判断会把开局玩家自己踢回旁观。
- “还有没有活人”按“在线连接的玩家名 + 队伍名”匹配 `PlayerTeam`，不看 `connection.player`：切出生点后连接上的
  team 指针会停留在旧的旁观队伍（实测 color=-3、wipe=true），而真实队伍仍有单位。
- 开局后 4 秒准备期内不做“无人则回大厅”判定：队伍和单位在 START 之后才建立，过早判定会把每局都立刻结束。
- 大厅地图预览按**地图名**解析，不用 `room.mapPath`：客户端的 `selectedMapPath` 只在开局包（packet 120）里刷新，
  大厅阶段 `engine.currentMapPath` 还是上一张/背景图，按路径查会一直拿到旧预览。
- 空房重置在托管策略里做：最后一个非旁观玩家离开大厅时恢复 `GameRoomSettings` 默认值并移除残留 AI 槽位；
  AI 槽位在引擎里 `isTeamSpectator==true`（`isAi = isTeamSpectator || isTeamControlledByAI`），只能按队伍颜色区分。
- 服务器直接托管自定义地图：把 `mods/maps` 同步到 `/var/lib/rwx/mods/maps`（818 MB，磁盘够）；
  服务器只按需求扫描清单，不会把地图读进内存，代价只有当前对局那一张。
- 项目记忆放 `.agent-memory/`，`agent-tmp/` 与 `agent-operation/tmp/` 只放临时产物：用户要求的目录约定。
