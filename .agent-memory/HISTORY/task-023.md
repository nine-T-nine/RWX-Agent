# task-023：事件 JSONL 接收器二次过滤（只存 update）

目标：订阅端收到的记录里混杂了传输元数据（实测一次 500 条抓包有 237 条 `cursor`），要求落盘前再过滤一次，只保留 `kind == "update"`。

改动：`agent-operation/tools/rwx-game-events-to-jsonl.py` 新增接收侧过滤 `KEPT_KINDS = {'update'}`，抽出 `catch_up()` 统一处理序列推进与落盘判定。`hello`、`event`、`remove`、`cursor` 不再写文件；`gap`/`overflow`/`parse_error`/`reset` 只输出到 stderr（`diagnostic()` 去掉写盘）。续传游标仍跟随每一条序列号，重连既不重放也不误报 `gap`；重启后从最后一条已存 `update` 续传，其后的非 `update` 会被重新请求并丢弃。同步 `agent-operation/tools/README.md` 4.2/4.3 与中英 `help.md`。

验证：用真实抓包 `agent-operation/tmp/.game-events.jsonl.23900.tmp`（500 条：181 `update`、237 `cursor`、56 `event`、26 `remove`，seq 432–931）经本地假服务端回放：落盘 181 条、`kinds={'update':181}`、序列与抓包中的 update 序列完全一致；复用同一输出文件再连一次，请求变为 `after=930`、`session` 保留、文件仍 181 条无重复。两轮均 PASS，临时脚本验证后删除。

遗留：只做了接收侧回放验证，未启动真实对局跑新协议流量；`--info`/`--allies` 现只影响服务端裁剪范围，对最终文件内容无直接差别。
