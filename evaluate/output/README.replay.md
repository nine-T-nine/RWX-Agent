# 回放命令文本样例（自动生成）

本目录同时存放两类生成物，都由工具自动生成、已在 `.gitignore` 中忽略：

| 文件 | 生成者 | 内容 |
|---|---|---|
| `commands-<时间>-pid<pid>-port<端口>-*.txt` 及 `-stats.txt` | [`CommandRecorder.java`](../utils/CommandRecorder.java) | 在线记录的 CLI 命令与统计 |
| `replay-*.txt` / `replay-*.jsonl` / `replays.index.txt` | [`replay.py`](../utils/replay.py) | 离线反解析 `.replay` 得到的命令执行文本 |

## 现有回放样例

由 `python evaluate/utils/replay.py <replay> --text --jsonl --out evaluate/output --name <别名>` 生成：

| 文件 | 来源回放 | 规模 |
|---|---|---|
| `replay-lake-2p.txt` / `.jsonl` | `[local] Lake (2p) [v1.15] (24 Oct 2026 18.02.00).replay` | 1161 条命令，2 队（Player + AI Impossible） |
| `replay-valley-pass-6p.txt` / `.jsonl` | `[local] Valley Pass (6p) [v1.15] (23 Oct 2026 10.04.08).replay` | 2868 条命令，6 队（1 人 + 5 AI） |
| `replay-island-freeze-4p.txt` | `[local] Island freeze (4p) [by hxyy] [v1.15] (24 Oct 2026 13.08.27).replay` | 18438 条命令，4 队 |
| `replays.index.txt` | 仓库 `replays/` 全部样本 | 每个文件的概要，含地图、队伍、帧数、命令分布、健康度 |

文本头部的 `# status :` 标明完整/部分解析；JSONL 每行一个命令对象，字段见
[`replay.md`](../utils/replay.md#输出长什么样)。

## 重新生成

```powershell
# 单个：文本 + JSONL
python evaluate/utils/replay.py "replays/[local] Valley Pass (6p) [v1.15] (23 Oct 2026 10.04.08).replay" `
  --text --jsonl --out evaluate/output --name replay-valley-pass-6p

# 全部样本的概要索引
$paths = (Get-ChildItem replays -Filter *.replay | ForEach-Object { $_.FullName })
python evaluate/utils/replay.py @paths --summary --out evaluate/output --index
```

> 注意：`replays/` 里的样本会随清理/新录制变化；上面的规模数字是生成时的快照。
