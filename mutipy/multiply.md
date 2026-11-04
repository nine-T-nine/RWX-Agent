# 多 DSH 同阵营：五条命令

一个游戏进程 + 每个智能体一条自己的 MCP 桥，都指向同一个 `agent-operation/tmp/port`。
所有脚本在 `X:\game\RWX\agent-tmp\team\`，配置（模型、桥、角色卡）也在那里。

## 1. 起游戏（一个窗口，开着别关）

```powershell
cd X:\game\RWX
.\gradlew.bat :desktop:run '--args=--evaluate 2'
```

等到日志出现 `RWX_CLI_TOOL_READY port=…`。**不要开第二个游戏进程。**

## 2. 起智能体（另开一个窗口，一条命令）

```powershell
cd X:\game\RWX
.\agent-tmp\team\start-team.ps1
```

它会：起 A1（负责开局）→ 轮询到 `match=1` → 再起 A2（作战）。
两个最小化的 PowerShell 窗口就是两个参战者，关掉窗口即停。

## 3. 结束

- 看到 `done=1`：两个窗口会自己退出，什么都不用做。
- 比赛结束但进程还活着：**发一次退出**，否则 `economy.log` 是 0 字节（证据没写完）。

```powershell
A:\miniconda\python.exe agent-operation\tools\rwx-cli.py rwx-exit
```

## 4. 收材料 + 打分（一条命令）

```powershell
.\agent-tmp\team\score.ps1 -Name 'Ice-Island-(2p)-20261104--15-31_xxx' -Clean
```

它按顺序做：收敛材料 → 改成你给的对局名（目录/回放/文本引用）→ 解析回放 → 预处理 →
两轮评分 → 刷新 meta → 删会话日志、重置信箱。最后打印总分。

不给 `-Name` 就用自动生成的名字（`Ice-Island-2p-4-11-2026-15.31.27` 这种）。

---

## 出问题看这里

| 现象 | 处理 |
|---|---|
| `游戏还没起：…` | 第 1 步还没就绪，等端口文件出现再跑第 2 步 |
| `MISSING_CREDENTIAL … OPENCODE_API_KEY` | `agent.ps1` 里从 `~\.local\share\opencode\auth.json` 取的密钥读不到 |
| `NO_ADAPTER` | 少传了 `--patch …\provider.yml`（用脚本不会漏） |
| `too many concurrent CLI clients (limit 8)` | 连接超过 8 条：B 的桥 + 你的 CLI + 别的 DSH 会话里的桥都算 |
| 两个 B 抢事件（`session-shared`） | 两份 `patch-a*.yml` 的 `--session` 写成一样了 |
| 会话中途停了 | headless 一问一答，本轮结束就退出；重跑第 2 步即可（游戏状态可重读） |

## 附：目录里都是什么

| 文件 | 作用 |
|---|---|
| `start-team.ps1` / `agent.ps1` | 一条命令起两个会话 / 单个会话的启动器 |
| `status-team.ps1` | 对局体检 |
| `score.ps1` | 收材料、改名、评分、清理 |
| `provider.yml` | 模型（deepseek-v4.1-flash / reasoning high） |
| `patch-a1.yml` / `patch-a2.yml` | 每人的桥（`--session` 不同，**不能一样**） |
| `card-a1.txt` / `card-a2.txt` | 角色卡：A1 经济/基建/生产执行，A2 作战/兵种配比 |
| `mail-a1.txt` / `mail-a2.txt` | 两人的信箱，脚本负责重置 |
| `last-run.txt` | 上一局的摘要 |

加第三个智能体：复制 `patch-a2.yml`→`patch-a3.yml`（改 `--session`）、`card-a2.txt`→`card-a3.txt`（写角色），
在 `start-team.ps1` 里照着 `Start-Agent 'a2'` 加一行。
