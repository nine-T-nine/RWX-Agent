# task-015：统一 game-state 与事件查询

- 目标：合并旧宏观摘要与实体关注视图，增加共用事件查询，移除旧命令。
- 实现：战局快照单次采集可见三方实体且不触发事件采样；文本和 JSON 共用报告数据，事件复用 `RwxEventCollector`、`RwxEventStore`、`RwxEventProcessor`；采样器故障时明确报错。更新 CLI 帮助和工具示例。
- 验证：隔离执行 `:core:test :desktop:headlessArchive`，24 套件、209 项通过；独立 Headless 房主与 Windows GUI/CLI 客户端启动对局，查询文本和 JSON，生产 Builder 后比较两个事件命令并验证 `--no-events` 不清空历史。
- 遗留：联机验证曾出现游戏线程查询超时，随后重新运行通过；事件阵营和时间边界由单元测试覆盖，未在真实多阵营战斗中逐项演练。
