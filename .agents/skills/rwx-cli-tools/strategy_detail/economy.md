# Economy and Expansion

> Evaluate the economy using **income multiplier, current income, available credits, number/level of resource deposits, factory throughput, current threats, and the next major expense**. Neither income nor balance alone is enough.
>
> **Income is reported in two units here; both are given.**
> - **GUI-native (per 40 ticks)**: the number the game interface and replays show, and the basis of human play experience; the engine really does settle income once per 40 ticks.
> - **Per game second**: the unit used by command output (`incomePerSecond` from `rwx-game-state`) and by evaluation; the conversion is ×1.5625 (1 tick = 16 ms, i.e. 62.5 ticks per game second ÷ 40).
>
> Example: "1 Command Center + 2 T1 extractors at x2" is `18×2 + 8×2×2 = 68` in the interface, i.e. **68 per 40 ticks ≈ 106.25 per game second**, which matches `incomePerSecond`.

## 1. Income Multiplier

| Multiplier | Typical economy | Development approach |
|---|---|---|
| x1 | Very limited | Protect resource deposits with cost-effective units; be cautious with expensive tech |
| x1.5 | Somewhat tight | Secure basic combat capability and expansion; avoid multiple expensive projects at once |
| x2 or higher | More comfortable | Upgrade resource extraction more aggressively, field mixed forces, and invest for the long term |

## 2. Spending Capacity by Income

| GUI-native (per 40 ticks) | Per game second | Rough tier | Suitable production options |
|---|---|---|---|
| ≤50 | ≤78 | Poor | T1 Tanks, a few Heavy/Plasma Tanks, basic Mechs, Anti-Air Mechs, Artillery Mechs, Interceptors and Gunships, Battleships |
| 50–100 | 78–156 | Typical opening | Units costing around 2,000 credits or less are generally viable |
| 100–200 | 156–312 | Comfortable | Many mid/high-tier units and stronger sustained attacks |
| ≥200 | ≥312 | Wealthy | Massed advanced forces and expensive defenses; possibly nuclear weapons when justified |

These are not strict purchase permissions or bans. Enemy threats, available cash, build time, and the existing army can override them.

## 3. Resource Deposit Expansion Rules

1. **Safe deposit available:** Usually prioritize securing sustainable income over simply accumulating credits.
2. **Contested unclaimed deposit:** Consider whether enemy control would create a serious income gap; provide an escort if worthwhile.
3. **Deposit inaccessible by land:** Use Naval Builders, transport, or another valid route. Do not repeatedly send ground units along an impossible path.
4. **Unsafe deposit:** Compare its strategic benefit with the cost of defending it. Do not sacrifice the army to capture every deposit.
5. **Owned deposits:** When the situation is stable, consider upgrading to T2/T3. In x2+ games, holding 2–3 T2+ deposits often supports a small effective attack.
6. **Overclock upgrade:** Consider the approximately 8,000-credit overclock mode when affordable. Shield mode generally offers less value for its cost.

## 4. When No Deposits Are Available

If the situation is stable, the multiplier is x2+, and there are no suitable deposits left, **Fabricators** can provide more income.

- Usually upgrade existing Fabricators to T2/T3 before building many T1 Fabricators.
- If an enemy attack is imminent, prioritize immediate fighting strength. Fabricators will not stop an approaching army.
- There is no fixed upper limit on economic growth in prolonged matches, so continued investment can be worthwhile.

## 5. Credit Management

- **Factory orders spend credits immediately.** Items in the production queue are not future unpaid expenses.
- If credits are piling up without a purpose, invest in income, production, or effective defense.
- If a crucial upgrade will soon be affordable, deliberately saving credits can make sense.
- Being able to afford one advanced unit does not mean its build time and escort needs are affordable.
- Postpone expansion if it would immediately leave essential forces or assets exposed.
