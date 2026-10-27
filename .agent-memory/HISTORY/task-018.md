# task-018：事件 JSONL 接收器文档与 500 条滚动上限

- 目标：在 `agent-operation/tools/README.md` 说明新增脚本的用途——监听游戏启动后另开的本机事件 Socket，把事件转成 JSONL 写入 `tmp`，并明确文件只保留最新 500 条记录；同时把该上限落实到接收器。
- 改动：
  - 接收器新增 `JsonlLog`：追加写入并在超过 `MAX_RECORDS = 500` 时重写为尾部 500 条（启动时若旧文件已超限也先压缩），保留最后会话/序列以便续传；`append()`/`receive()` 接口不变。
  - `tools/README.md` 明确「游戏启动后除命令端口外另开事件 Socket 端口」、脚本监听该端口写入 `agent-operation/tmp/game-events.jsonl`、500 条滚动上限；表格条目同步。
  - `help_zh.md` 6.1 与英文 `help.md` 同步说明 500 条上限和独立端口；`START.md` 补「只保留最新 500 条」。
  - 测试：接收器 7 项（新增 500 条压缩、启动时压缩旧文件）。
- 验证：`python agent-operation/tools/test_game_events_to_jsonl.py` 7 项通过；文档中英结构一致。
- 遗留：500 条是接收端滚动窗口，超出部分不再落盘；与游戏端 180 秒/1000 条缓存无强绑定。
