# task-003（已完成，2026-10-25）

## 目标

云端常驻房间 + 大厅自动选举/移交房主 + 客户端自由 GUI/无 GUI；实现验证后部署到云服务器（具体地址已脱敏）。

## 改动

- 新增 `RwxManagedRoom.kt`：大厅按加入顺序选举、退出接替、对局中冻结；代理房主 GUI/CLI 请求经原版聊天包
  转给服务器校验执行；无人时 `sendReturnToBattleroomEvent` 回大厅；中途加入自动观战。
- `rwx-host --managed-room true`；`connection-status` 增加 `roomAdmin/roomManager/spectator`。
- GUI/CLI 代理房主的选图、设置、加 AI、开局转发到服务器执行。
- 网络修复：客户端每局补发 packet 112 的 loading 状态（原版每连接只发一次，返回大厅清 D 后第二局会空等 15 秒）；
  `aB()` 重新武装。
- CLI 出生点受地图容量限制（`MapMetadata.getNumberOfPlayersInMap`），避免地图外的空出生点。
- 托管策略：按开局时的玩家名字判断“新身份”；按连接名 + 队伍名判断“还有活人”；开局 4 秒内不判定。
- 新工具 `agent-operation/tools/verify-managed-room.py`；部署脚本 `install-cloud.sh`、`ssh-run.py`、`sftp-put.py`
  转正到 `agent-operation/server/`；新增 `agent-operation/DEPLOYMENT.md`。

## 验证

- 本地：`verify-managed-room.py --rounds 2` 全流程 PASS（含中途加入与两局各 8 秒保持）。
- 云端（公网）：首个客户端成为管理员并开局，tick 正常推进；第二客户端中途加入为观战；管理员退出后回大厅、
  他人接任；第二局正常推进。
- 198 项单测通过。

## 遗留

- 手动 GUI 通过游戏内菜单输入地址加入服务器尚未实测（只用过 `--join-server=`）。
- 云端人工操控（截图/键鼠）只做过本地验证。
- 工作区改动未提交。
