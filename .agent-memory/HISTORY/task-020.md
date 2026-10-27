# task-020：清理 agent-operation/tools 的多余文件

- 目标：删掉 `agent-operation/tools` 下已无用途的文件与脚本，并让文档/帮助文案不留下悬空引用。
- 改动：
  - 删除 `rwx-cli-daemon.ps1`（292 行旧版文件邮箱 daemon）：除自身帮助与 `tools/README.md` 5.1 外无任何引用；`rwx-cli.sh` 明确写了 no daemon，`rwx-cli.py --batch/--interactive` 与 `rwx-cli.ps1 -Batch/-Interactive` 已覆盖同一需求。
  - 同步引用：`tools/README.md` 去掉工具表那一行、删除 5.1 整节（原 5.2 截图改为 5.1）；`rwx-cli.ps1` 的 `.NOTES` 去掉指向 daemon 的一句；`rwx-cli-lib.ps1` 头注释改为「rwx-cli.ps1 与 .agents 技能脚本共用」。
  - 删除 `__pycache__/`（gitignore 已覆盖，仍会再生成）与空目录 `tools/tmp/`。
  - 按用户选择保留 `experimental_scripts/` 全目录（6 个旧宿主 JS 参考脚本 + 未跟踪的 `minigun-shield-kite-return.live.json`）。
  - 未动 `verify-managed-room.py`：task-003 的托管房间多轮联机验收工具，仍是唯一能覆盖多进程多局流程的手段（只是没写进 README 工具表）。
- 验证：全仓检索 `rwx-cli-daemon` / `文件邮箱` / `req.txt` / `resp.txt` 无残留；`rwx-cli.ps1`、`rwx-cli-lib.ps1`、`screenshot.ps1` 用 PowerShell Parser 解析无错误；`git status` 无意外改动（用户并行编辑的 `tools/README.md` 2.2 节保持原样）。
- 遗留：README 工具表未收录 `verify-managed-room.py` 与 `test_game_events_to_jsonl.py`；需要时可补两行。
