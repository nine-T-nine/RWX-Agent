# task-016：实体查询与事件流推送

- 目标：完成上一轮未收尾的实体查询重组与事件 Socket/JSONL 接收器，补齐测试、真实对局验证与文档同步。
- 改动：
  - `unit-list` 新增 `--owner any`（所有可观测阵营；敌人仍要求当前可见）；`unit-list` 与 `threat-list` 共用 `matchesVisibleOwner` 的实时可见性/阵营范围，历史位置不参与实体查询。
  - 威胁分类抽成 `threatProfile`（未武装单位不再计为防空/反装甲）；威胁输出补可见性行、最近实体与空区块说明，移除内部 100 条统计截断。
  - 新增 `RwxEventStream`：127.0.0.1 独立端口、NDJSON 协议（`hello`/`event`/`update`/`remove`/`cursor`/`gap`/`overflow`/`reset`）、最多 1000 条传输日志、每连接独立游标与线程；聚合身份跨帧匹配（贡献事件对象身份；源被淘汰或 3 秒窗口内重新发现时回退目标 ID）。
  - `agent-operation/tools/rwx-game-events-to-jsonl.py`：读端口发现文件、按日志末尾会话/序列续传、序列去重、`gap`/`parse_error` 诊断，默认追加 `agent-operation/tmp/game-events.jsonl`。
  - 帮助：`help_zh.md` 增补事件流协议与接收器，`help.md` 同步英文；`START.md`、`tools/README.md` 补端口发现、续传、多订阅与慢客户端说明。
- 验证：
  - 隔离 `:core:test :desktop:headlessArchive` 通过：26 套件、224 项、零失败；新增事件流 8 项、威胁与可见性 6 项，接收器 5 项模拟测试通过。
  - 真实 Headless 对局（King of the Middle 3p）：建造工厂与生产单位产生 `info` 事件，敌方进入视野产生 `new` 聚合；JSONL 共 11 条、`seq` 1..9 连续且无重复，聚合以 `update`/`revision` 递增；接收器重启后从日志末尾续传且不重复；`unit-list --owner any|enemy`、`threat-list`、`game-state --no-events` 与文档一致。
- 遗留：慢客户端只阻塞自身连接（每连接独立线程，最多 16 个并发订阅）；队列溢出只发 `overflow` 诊断，落入被丢弃帧的瞬时记录无法补全；进程重启不能恢复旧会话的内存日志。
