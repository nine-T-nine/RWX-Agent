# RWX Agent Strategy Guide

> **Default entry point for live matches.** Read this file first, then consult a topic under `strategy_detail/` only when needed.
>
> These are practical guidelines, not a guaranteed winning script. The current game state, enemy behavior, and available actions always take precedence over this document. CLI usage, sockets, event subscriptions, and polling intervals are out of scope; follow `help.md` and the startup documentation for communication details.

## 1. Decision Priorities

| Priority | Trigger | Immediate response |
|---|---|---|
| P0 — Survival | Command Center in imminent danger; main army about to be destroyed | Reinforce, retreat, or eliminate the main threat immediately. Do not wait for perfect intelligence. |
| P1 — Major threat | Enemy artillery or air swarm appears; a mining area is attacked; a critical defense fails | Identify the threat and its counters. Reposition existing units or adjust production and defenses. |
| P2 — Tactical opportunity | Enemy main force is exposed; a weak outpost can be taken; a favorable trade is available | Exploit the opportunity while keeping losses acceptable. |
| P3 — Expansion | A safe resource deposit is available; long-term income is inadequate | Secure deposits, upgrade the economy, and add essential defenses. |
| P4 — Routine optimization | No urgent threats | Improve army composition, production, tech, and scouting. |

Prioritize by **actual expected loss or benefit**, not labels alone. A minor raid may not justify abandoning an attack that could decide the match.

## 2. Minimal Decision Loop

1. **Identify changes:** Which units or assets are taking damage or being lost? Where is the enemy? Which observations are stale?
2. **Pick one primary objective:** Protect a key asset, counter the biggest threat, exploit an attack window, or grow the economy.
3. **Choose the cheapest effective response:** Reposition available forces first; build, upgrade, or queue more units when existing forces are insufficient.
4. **Check major risks:** Weapon ranges, ground/air targeting, route accessibility, movement-speed mismatch, available funds, and production time.
5. **Act and verify:** Issuing an order does not mean the objective is complete. Reassess when results differ from expectations; do not repeat a failed plan mechanically.

Routine actions do not require complete information. **Handle emergencies immediately.** An enemy hidden by fog of war must not be treated as gone.

## 3. Quick Counter Reference

| Main enemy threat | Consider first | Avoid |
|---|---|---|
| Swarms of fragile ground units (Tanks, basic Mechs, Hover Tanks) | Flamethrower turrets, artillery, area damage | Unsupported low-tier units fighting head-on |
| Mid/high-tier Mechs or shielded units | Tesla Towers / T2 Tesla Towers, artillery | Relying only on standard machine-gun defenses |
| Enemy artillery position | Laser Defense Turrets, friendly artillery, flanking, or withdrawal from its range | Repeatedly sending dense Tank groups into artillery fire |
| Swarms of fragile aircraft such as Gunships | T2 Anti-Air Turrets, Anti-Air Mechs, Interceptors, Flak Guns | Expecting T1 anti-air alone to protect the base |
| Advanced heavy aircraft | Concentrated T3 anti-air, AA Fighters, Heavy Interceptors, etc. | Scattered, inadequate anti-air firepower |
| Minigun Mechs | Tesla Towers, artillery, Mammoth Tanks | Sending low-tier ground units in successive waves |
| Battleships near the coast | Long-range anti-ship weapons, aircraft, or other naval counters | Wasting units that cannot attack naval targets |
| Isolated, lightly defended outpost | Rush, flank, long-range pressure, or Builder-assisted push | Unnecessary direct engagement with the enemy main force |

These are counter *options*, not guaranteed outcomes. **Numbers, range, terrain, access, and engagement time** determine whether a response will work.

## 4. Economy at a Glance

- **≤50 income/s:** Limited economy. Prefer inexpensive effective units, resource deposits, and essential defenses.
- **50–100 income/s:** Typical early-game income. Units costing roughly 2,000 or less are reasonable options.
- **100–200 income/s:** Can support stronger attacks and some mid/high-tier units.
- **≥200 income/s:** Can sustain mixed forces and expansion; expensive experimental units or nuclear weapons may become viable in long games.
- **Income multiplier x1 / x1.5 / x2+:** Roughly means very tight / somewhat tight / more comfortable. These are guidelines, not hard thresholds.
- **Factory orders deduct credits immediately.** Do not leave large sums unused without a clear reason such as saving for an upgrade, expensive unit, or emergency response.

## 5. Three Mandatory Corrections

- **Sudden heavy losses:** Immediately check for artillery, area damage, or a strong unit counter. Do not resend the same type of army without changing the conditions.
- **Important base under attack:** Do not ignore threats to mining areas, factories, or the Command Center just because the main force is executing an older plan.
- **Vision lost:** Last-known enemy positions are unconfirmed intelligence, not evidence that the enemy retreated or was destroyed.

RWX is a fast RTS. Even a short period without a response can change the outcome. Avoid unnecessary waiting, but do not spam pointless commands merely to act frequently.

## 6. When to Read the Details

| Current question | Read only this file |
|---|---|
| How to defend the base, counter air/artillery, choose turrets, or repair units | [defense.md](strategy_detail/defense.md) |
| How to compose an attack force, break defenses, airdrop, or rush | [offense.md](strategy_detail/offense.md) |
| How to spend credits, secure deposits, expand, or upgrade | [economy.md](strategy_detail/economy.md) |
| How to handle fog of war, uncertain intelligence, or reconnaissance | [scouting.md](strategy_detail/scouting.md) |
| How to handle mountains, islands, sea crossings, and routes | [terrain.md](strategy_detail/terrain.md) |
| How game mechanics work and when to use niche units | [mechanics.md](strategy_detail/mechanics.md) |

**Do not read all detail files by default.** If no special issue arises, this file is sufficient for decision-making.
