# CURRENT

更新时间：2026-10-26

## 目标

修完大厅地图显示/自定义地图/空房重置三处问题，并完成云端部署。部署地址已从文档脱敏。

## 本轮已完成并验证（云端实测）

- **大厅地图图片不更新**：客户端在大厅里的 `room.mapPath` 只在开局时刷新（`selectedMapPath`/`engine.currentMapPath`），
  所以换图后名字变了、图片还是上一张。改为按同步过来的**地图名**解析预览（`previewForName`，命中缓存），
  官方图和自定义图都能随选随换。云端实测：Lake → Big Island → 自定义图，名称与图片同步变化。
- **自定义地图选不了/名称不变**：根因是服务器上没有这些图，`-map <name>` 被服务器以 `Map is not enabled` 拒绝。
  已把 `mods/maps`（326 张图 + 预览，818 MB）同步到服务器 `/var/lib/rwx/mods/maps`，现在云端可直接选自定义图，
  客户端只要本地也有同名图就能显示预览、进游戏。
- **空房不重置**：最后一个非旁观玩家离开大厅时，托管策略把房间设置恢复默认并清掉残留 AI 槽位
  （AI 槽位在引擎里是 `isTeamSpectator`，所以按队伍颜色判断）。云端实测：收入 3.0 + 1 个 AI → 全部退出 →
  重新进入后收入 1.0、AI 槽位消失。
- **服务端已更新**：release `20261025-113404-1237146b38d7`，19:34 重启，日志可见 `RWX_ROOM_RESET`。

## 验证记录

- 本地：官方图切换、自定义图切换（名称+预览）、空房重置全部通过；198 项单测通过。
- 云端：同一套流程重跑通过；`verify-managed-room.py` 的多局流程此前已通过。

## 下一步

- 客户端产物已用新代码重出：`desktop/build/compose/binaries/main/exe/RWX-1.1.3.exe`（19:38）、
  `android/build/outputs/apk/{debug,release}/`（19:39/19:42）。
- 手动 GUI 从游戏内菜单输入地址加入（未实测，仍走 `--join-server=`）。
- 工作区改动未提交。

## 现场状态

- 云端：`/opt/rwx/current` → `releases/20261025-113404-1237146b38d7`；磁盘 50G 用 12G，剩 36G；
  `/tmp` 旧安装包已清理。
- 地图：`/var/lib/rwx/mods/maps` 652 个文件，818 MB。
