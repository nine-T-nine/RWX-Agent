# task-004（2026-10-25）

## 目标

修三处大厅问题并确认服务端更新：换图后预览不更新、自定义地图选了没反应、空房后设置残留。

## 根因与改动

- 预览不更新：客户端大厅里的 `room.mapPath` 来自 `selectedMapPath ?: engine.currentMapPath`，
  两者都只在开局包刷新，换图后仍是旧路径，`previewFor` 就走了旧缓存。改为按同步的**地图名**在
  `enabledMaps()` 里查 `MapEntry.previewAssetPath`（按名字缓存），路径解析只作兜底。
- 自定义地图没反应：服务器上没有该图，`-map <name>` 被 `Map is not enabled` 拒绝（大厅只看到聊天错误）。
  把 `mods/maps` 全量同步到服务器数据目录，服务器现在能解析并应用这些图；客户端本地也需要同名图才能加载/预览。
- 空房不重置：`RwxManagedRoom` 在最后一个非旁观玩家离开大厅时恢复 `GameRoomSettings` 默认值，
  并按队伍颜色移除残留槽位（AI 槽位 `isTeamSpectator==true`，按旧条件会漏掉）。

## 验证

- 本地与云端各跑一遍：官方图切换（Lake → Big Island）、自定义图切换，名称+预览都同步；
  收入 3.0 + AI → 全员退出 → 重进后收入 1.0、AI 消失。
- 198 项单测通过；`verify-managed-room.py` 多局流程此前通过。

## 遗留

- 客户端 exe/APK 需用新代码重出（构建中）。
- 从游戏内菜单手动输入地址加入仍未实测。
