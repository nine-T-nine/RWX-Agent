# map-show 编码表 / map-show code table

`rwx-game-map-show --unit-*` 的四字符类型编码全表。

运行时通常**不需要**查这张表：地图输出后面的 `terrain:` / `faction:` / `units:` / `own:` 图例已经解释了本次实际出现的所有符号，只有要预先理解编码体系或排查异常编码时才需要本表。

Full four-character type codes for `rwx-game-map-show --unit-*`. You normally do **not** need this table at run time: the `terrain:` / `faction:` / `units:` / `own:` legends after the grid explain every symbol that actually appears.

## 规则 / Rules

- 5 字符格 = `<阵营><4 字符类型编码>`：`+` + 小写 = 我方，`+` + 大写 = 友军，`-` + 大写 = 敌军，`-` + 小写 = 中立。
- 单位编码为「生产来源 + 3 字母」：`L`=T2 陆军工厂、`N`=T2 海军工厂、`M`=T2 机甲工厂、`A`=空军基地、`E`=实验工厂；建造者与战斗工程师归 `L` 类。
- 建筑编码为 `b` + 3 位语义字符，等级/模式体现在编码里；资源提取器固定为 `b` + `a-e` + `ex`（`a/b/c` = T1/T2/T3，`d` = 护盾模式，`e` = 超频模式）。
- 下表均为小写基形；友军/敌军把 4 字符编码转成大写。同一类型只有一套映射，不区分大小写。
- 隐藏形态（飞行/着陆/下潜/展开等）与基类型共用同一个编码，状态切换不改变编码。

Cell = `<faction><4-char code>`: `+` lower = own, `+` upper = ally, `-` upper = enemy, `-` lower = neutral.
Units are `L/N/M/A/E` + 3 letters (production source); buildings are `b` + 3 semantic characters.
Codes below are the lowercase base form; allies and enemies render them uppercase.


## 单位编码 / Unit codes

**L — T2 land factory（T2 陆军工厂）**

| Code | Internal type ID | English name |
|---|---|---|
| `ltnk` | `c_tank` | tank |
| `lhov` | `hoverTank` | hover_tank |
| `lbld` | `builder` | builder |
| `lhvc` | `hovercraft` | hovercraft |
| `lsct` | `scout` | scout |
| `lhtk` | `heavyTank` | heavy_tank |
| `lart` | `c_artillery` | artillery |
| `lpls` | `plasmaTank` | plasma_tank |
| `lhht` | `heavyHoverTank` | heavy_hover_tank |
| `llsr` | `c_laserTank` | laser_tank |
| `lmsl` | `missileTank` | missile_tank |
| `lhar` | `heavyArtillery` | heavy_artillery |
| `leng` | `combatEngineer` | combat_engineer |
| `lmam` | `c_mammothTank` | mammoth_tank |
| `ltds` | `tankDestroyer` | tank_destroyer |
| `lmga` | `megaTank` | mega_tank |
| `lbug` | `ladybug` | ladybug |

**N — T2 naval factory（T2 海军工厂）**

| Code | Internal type ID | English name |
|---|---|---|
| `ngbt` | `gunBoat` | gun_boat |
| `nlsb` | `lightSub` | light_sub |
| `nbld` | `builderShip` | builder_ship |
| `nsub` | `attackSubmarine` | attack_submarine |
| `nmsh` | `missileShip` | missile_ship |
| `nbtl` | `battleShip` | battle_ship |
| `nhaa` | `heavyAAShip` | heavy_aa_ship |
| `nhvs` | `heavySub` | heavy_sub |
| `nhbt` | `heavyBattleship` | heavy_battleship |
| `nhms` | `heavyMissileShip` | heavy_missile_ship |
| `nntl` | `nautilusSubmarine` | nautilus_submarine |
| `ncar` | `experiementalCarrier` | experimental_carrier |

**M — T2 mech factory（T2 机甲工厂）**

| Code | Internal type ID | English name |
|---|---|---|
| `mgun` | `mechGun` | mech_gun |
| `mmsl` | `mechMissile` | mech_missile |
| `mart` | `mechArtillery` | mech_artillery |
| `mbkr` | `mechBunker` | mech_bunker |
| `meng` | `mechEngineer` | mech_engineer |
| `mmgn` | `mechMinigun` | mech_minigun |
| `mltn` | `mechLightning` | mech_lightning |
| `mlsr` | `mechLaser` | mech_laser |
| `mhms` | `mechHeavyMissile` | mech_heavy_missile |
| `mflm` | `mechFlame` | mech_flame |
| `mfly` | `mechFlyingLanded` | mech_flying |

**A — air base（空军基地）**

| Code | Internal type ID | English name |
|---|---|---|
| `algs` | `lightGunship` | light_gunship |
| `aint` | `c_interceptor` | interceptor |
| `ahel` | `c_helicopter` | helicopter |
| `adrp` | `dropship` | dropship |
| `agun` | `gunShip` | gunship |
| `ahin` | `heavyInterceptor` | heavy_interceptor |
| `aspy` | `spyDrone` | spy_drone |
| `ajet` | `amphibiousJet` | amphibious_jet |
| `amsh` | `missileAirship` | missile_airship |
| `abmb` | `bomber` | bomber |
| `abem` | `aaBeamGunship` | aa_beam_gunship |

**E — experimental factory（实验工厂）**

| Code | Internal type ID | English name |
|---|---|---|
| `ebee` | `fireBee` | fire_bee |
| `extk` | `c_experimentalTank` | experimental_tank |
| `exhv` | `experimentalHoverTank` | experimental_hover_tank |
| `exdp` | `experimentalDropship` | experimental_dropship |
| `exsp` | `experimentalSpider` | experimental_spider |
| `exgs` | `experimentalGunship` | experimental_gunship |
| `emod` | `modularSpider` | modular_spider |

## 建筑编码 / Building codes

`b` + 3 位语义；同一建筑类型的等级体现在编码里，升级后换用对应等级的编码。

| Code | Internal type ID | English name | Level |
|---|---|---|---|
| `bcmd` | `commandCenter` | command_center |  |
| `blf1` | `landFactory` | land_factory_t1 | T1 |
| `blf2` | `landFactory` | land_factory_t2 | T2 |
| `baf1` | `airFactory` | air_factory_t1 | T1 |
| `baf2` | `airFactory` | air_factory_t2 | T2 |
| `bsf1` | `seaFactory` | sea_factory_t1 | T1 |
| `bsf2` | `seaFactory` | sea_factory_t2 | T2 |
| `bmf1` | `mechFactory` | mech_factory_t1 |  |
| `bmf2` | `mechFactoryT2` | mech_factory_t2 |  |
| `belf` | `experimentalLandFactory` | experimental_factory |  |
| `blab` | `laboratory` | laboratory |  |
| `baex` | `extractorT1` | extractor_t1 |  |
| `bbex` | `extractorT2` | extractor_t2 |  |
| `bcex` | `extractorT3` | extractor_t3 |  |
| `bdex` | `extractorT3_reinforced` | extractor_t3(shield) |  |
| `beex` | `extractorT3_overclocked` | extractor_t3(overclock) |  |
| `bfa1` | `fabricatorT1` | fabricator_t1 |  |
| `bfa2` | `fabricatorT2` | fabricator_t2 |  |
| `bfa3` | `fabricatorT3` | fabricator_t3 |  |
| `btur` | `c_turret_t1` | turret_t1 |  |
| `btg2` | `c_turret_t2_gun` | turret_t2_gun |  |
| `btg3` | `c_turret_t3_gun` | turret_t3_gun |  |
| `bta1` | `c_turret_t1_artillery` | turret_t1_artillery |  |
| `bta2` | `c_turret_t2_artillery` | turret_t2_artillery |  |
| `btf2` | `c_turret_t2_flame` | turret_t2_flame |  |
| `btl1` | `c_turret_t1_lightning` | turret_t1_lightning |  |
| `btl2` | `c_turret_t2_lightning` | turret_t2_lightning |  |
| `baa1` | `c_antiAirTurret` | anti_air_turret_t1 |  |
| `baa2` | `c_antiAirTurretT2` | anti_air_turret_t2 |  |
| `baa3` | `c_antiAirTurretT3` | anti_air_turret_t3 |  |
| `baaf` | `antiAirTurretFlak` | anti_air_turret_flak |  |
| `bop1` | `outpostT1` | outpost_t1 |  |
| `bop2` | `outpostT2` | outpost_t2 |  |
| `bldf` | `laserDefence` | laser_defence |  |
| `brpr` | `repairbay` | repair_bay |  |
| `bnuk` | `nukeLauncherC` | nuke_launcher |  |
| `banu` | `antiNukeLauncherC` | anti_nuke_launcher |  |


## 未纳入编码 / Excluded

以下类型不参与二维网格编码（不显示，也不计入 `own units`）：

- 模组专属建筑：墙 `wall_v`、补给站 `supplyDepot`。
- 虫族、场景道具（树、水晶、补给箱、火焰、编辑器标记等）与第三方模组单位。

墙、补给站属于模组扩展，不混入原版建筑编码表；其余类型在正常对局中不会出现，或根本不是可指挥实体。

