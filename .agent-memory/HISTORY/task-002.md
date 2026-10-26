# task-002（2026-10-25）

## 目标

统一 CLI 命令为 `rwx-*`；新增远程连接 `rwx-connect`、Headless 模式、CLI 端口自动发现；
检查工具脚本与文档；给出 Ubuntu 云服务器部署方案。

## 改动

- 命令重命名 + 移除旧别名，同步 `help.md`、脚本、测试。
- `rwx-connect` / 连接状态 / 断开 / 重连 / 托管 / 退出；复用原版直连与中继描述符解析。
- 真 Headless：`desktop/.../headless/`（HeadlessMain/GameSession/Graphics），无窗口、图形上下文、音频设备。
- 端口文件：`RwxCliPortFile`，原子写、多实例记录、崩溃接替、退出清理；`agent-operation/tmp/port`。
- 部署材料：`agent-operation/server/`（systemd 模板、host-service）、`SERVER.md`、`VALIDATION.md`。
- 修实测问题：Headless 大厅提前调度导致 ready 丢失、GUI 重连丢开局事件、多人 `step` 误暂停、
  静默对端 socket 不释放、PowerShell 5.1 中文脚本编码。

## 验证

- 195 项单测通过；Windows GUI、Windows Headless、Ubuntu WSL 三方同时入房、自动开局、指令传播、权限拒绝、原席位重连。
- 发行包在仓库外解压运行（WSL 无 DISPLAY）。
- RELAY-CN：能发现并建立 TCP，但入口返回 `Your client is newer then the server`，未能加入。

## 遗留

- 云服务器当时按要求只交付教程，未登录、未验证公网/物理局域网/UDP。
