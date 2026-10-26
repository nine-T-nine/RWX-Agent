# task-013：本地战役对局

目标：启动本地战役房间，待用户配置后开局并操控作战。

改动：无源码改动；启动 GUI 与 RWX CLI，进入并开始 `水田2P`、Player 对 Very Hard AI、收入 2.0 的对局。

验证：CLI 确认对局开始；最终 `rwx-game-state --summary` 显示 08:53、`outcome: defeat`、我方单位 0。

遗留：若重开，开局立刻生产建造者、部署矿机/陆军工厂与防御，不能只启动后等待。
