package com.corrodinggames.rts.game.ai.custom.rwx_cli_tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CLI formatter and dispatcher for implemented {@code rwx-game-*} operations. */
public final class RwxLocalGameCommands implements RwxCliCommandExecutor {
    /** {@code unit-list} without {@code --hp} / {@code --full} lists entities under this health. */
    private static final float UNIT_LIST_DEFAULT_HP = 80.0f;

    private final RwxLocalGameService service;

    public RwxLocalGameCommands() {
        this(new RwxEngineLocalGameService());
        // Start the alert sampler as soon as the app session exists. It is a no-op until a local
        // game is loaded, and it keeps event observation active even when no
        // CLI command is polling (the old lazy sampling only produced events on request).
        if (this.service instanceof RwxEngineLocalGameService) {
            ((RwxEngineLocalGameService) this.service).startObservationSampler();
        }
    }

    RwxLocalGameCommands(RwxLocalGameService service) {
        this.service = service;
    }

    @Override
    public String execute(RwxCliCommands.Command command, List<String> arguments) {
        if (command == RwxCliCommands.Command.STATE) {
            validateOptions(arguments, new String[] {"--event-seconds", "--event-types"},
                    "--json", "--summary", "--units-acting", "--units-damaged",
                    "--building-acting", "--building-actionable", "--event-allies", "--no-events");
            boolean summary = optionalFlag(arguments, "--summary");
            if (summary) {
                for (String flag : new String[] {"--units-acting", "--units-damaged",
                        "--building-acting", "--building-actionable"}) {
                    if (optionalFlag(arguments, flag)) {
                        throw new IllegalArgumentException(flag + " does not apply to --summary");
                    }
                }
            }
            boolean noEvents = optionalFlag(arguments, "--no-events");
            String rawSeconds = optionalValue(arguments, "--event-seconds");
            String rawTypes = optionalValue(arguments, "--event-types");
            boolean allies = optionalFlag(arguments, "--event-allies");
            if (noEvents && (rawSeconds != null || rawTypes != null || allies))
                throw new IllegalArgumentException("--no-events conflicts with event filters");
            double seconds = rawSeconds == null ? 30 : parseDouble("--event-seconds", rawSeconds);
            if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 180)
                throw new IllegalArgumentException("--event-seconds must be greater than 0 and at most 180");
            RwxLocalGameTypes.EventQuery eventQuery = noEvents ? null : new RwxLocalGameTypes.EventQuery(
                    parseEventTypes(rawTypes, "--event-types"), seconds, allies);
            boolean unitsActing = optionalFlag(arguments, "--units-acting");
            boolean unitsDamaged = optionalFlag(arguments, "--units-damaged");
            boolean buildingActing = optionalFlag(arguments, "--building-acting");
            boolean buildingActionable = optionalFlag(arguments, "--building-actionable");
            boolean defaults = !unitsActing && !unitsDamaged && !buildingActing && !buildingActionable;
            RwxLocalGameTypes.GameStateSnapshot snapshot = service.state(RwxLocalGameTypes.UnitOwner.SELF);
            RwxLocalGameTypes.PendingPage pending = null;
            try { pending = service.pending(); } catch (UnsupportedOperationException ignored) { }
            RwxLocalGameTypes.EventPage events = noEvents ? null : service.events(eventQuery);
            if (!noEvents && events == null) throw new IllegalStateException("Event service unavailable");
            return RwxGameStateReport.render(snapshot, pending, events, seconds,
                    optionalFlag(arguments, "--json"), summary, defaults || unitsActing, defaults || unitsDamaged,
                    defaults || buildingActing, defaults || buildingActionable);
        }
        if (command == RwxCliCommands.Command.PAUSE) {
            return formatPause(service.setPaused(parseBooleanOption(arguments, "--value")));
        }
        if (command == RwxCliCommands.Command.SPEED) {
            float speed = parseFloat("--value", requiredFloatOption(arguments, "--value"));
            if (speed < 1.0f || speed > 64.0f) {
                throw new IllegalArgumentException("--value must be between 1 and 64; use "
                        + RwxCliCommands.Command.PAUSE.commandName() + " to pause");
            }
            return format(service.setSpeed(speed));
        }
        if (command == RwxCliCommands.Command.TEAM_LIST) {
            requireNoArguments(arguments);
            return formatTeams(service.teams());
        }
        if (command == RwxCliCommands.Command.UNIT_LIST) {
            boolean delta = optionalFlag(arguments, "--delta");
            boolean compact = optionalFlag(arguments, "--compact");
            boolean json = optionalFlag(arguments, "--json");
            boolean summary = optionalFlag(arguments, "--summary");
            if (delta && json) {
                throw new IllegalArgumentException("--delta only applies to text output (JSON never carries changes)");
            }
            if (summary) {
                if (delta || compact || json) {
                    throw new IllegalArgumentException(
                            "--summary cannot be combined with --delta, --compact or --json");
                }
                if (optionalValue(arguments, "--hp") != null || optionalFlag(arguments, "--full")) {
                    throw new IllegalArgumentException("--summary always counts every match, so --hp/--full do not apply");
                }
                RwxLocalGameTypes.UnitPage page = service.units(parseUnitQuery(arguments, true, true));
                StringBuilder output = new StringBuilder("单位汇总 (tick ").append(page.tick)
                        .append(", time ").append(RwxCliText.time(page.gameTimeMillis)).append(")")
                        .append("\ncount: ").append(page.units.size());
                appendSummary(output, page.units);
                return output.toString();
            }
            // Observations are read-only by default: only an explicit --delta advances the change
            // baseline, so repeated plain unit-list calls do not silently eat the events.
            return formatUnits(service.units(parseUnitQuery(arguments, !delta)),
                    compact, json, delta);
        }
        if (command == RwxCliCommands.Command.ACTIONS) {
            validateOptions(arguments, new String[] {"--unit"}, "--json");
            long actionUnitId = parseLong("--unit", requiredOption(arguments, "--unit"));
            return formatActions(actionUnitId, service.actions(actionUnitId),
                    optionalFlag(arguments, "--json"));
        }
        if (command == RwxCliCommands.Command.UNIT_SHOW) {
            long unitId = requiredLongOption(arguments, "--id");
            String text = formatUnit(service.unit(unitId));
            // Pending (--allow-unaffordable) orders are NOT in the engine queue yet; say so here
            // so a factory does not look idle when the CLI is still waiting for credits.
            List<RwxLocalGameTypes.PendingOrderSnapshot> pending = new ArrayList<>();
            for (RwxLocalGameTypes.PendingOrderSnapshot order : service.pending().orders) {
                if (order.unitIds.contains(unitId)) pending.add(order);
            }
            if (!pending.isEmpty()) {
                StringBuilder extra = new StringBuilder("\ncliPending (未进入引擎队列): ")
                        .append(pending.size());
                for (RwxLocalGameTypes.PendingOrderSnapshot order : pending) {
                    extra.append("\n  #").append(order.id).append(' ').append(order.kind)
                            .append(order.actionId == null ? "" : " " + order.actionId)
                            .append(" | ").append(order.reason);
                }
                text += extra.toString();
            }
            return text;
        }
        if (command == RwxCliCommands.Command.ORDER_MOVE) {
            return formatReceipt(service.issue(parsePointOrder(arguments, RwxLocalGameTypes.OrderType.MOVE, true)));
        }
        if (command == RwxCliCommands.Command.ORDER_ATTACK_MOVE) {
            return formatReceipt(service.issue(parsePointOrder(arguments, RwxLocalGameTypes.OrderType.ATTACK_MOVE, true)));
        }
        if (command == RwxCliCommands.Command.ORDER_ATTACK) {
            if (optionalValue(arguments, "--target") == null) {
                if (optionalValue(arguments, "--x") != null || optionalValue(arguments, "--y") != null) {
                    throw new IllegalArgumentException(
                            "--x/--y are not supported by attack; use "
                                    + RwxCliCommands.Command.ORDER_ATTACK_MOVE.commandName()
                                    + " to advance to a point");
                }
                throw new IllegalArgumentException("--target is required");
            }
            return formatReceipt(service.issue(parseTargetOrder(arguments, RwxLocalGameTypes.OrderType.ATTACK)));
        }
        if (command == RwxCliCommands.Command.ORDER_STOP) {
            return formatReceipt(service.issue(parseStopOrder(arguments)));
        }
        if (command == RwxCliCommands.Command.ORDER_PATROL) {
            return formatReceipt(service.issue(parsePointOrder(arguments, RwxLocalGameTypes.OrderType.PATROL, true)));
        }
        if (command == RwxCliCommands.Command.ORDER_GUARD) {
            return formatReceipt(service.issue(parseTargetOrder(arguments, RwxLocalGameTypes.OrderType.GUARD)));
        }
        if (command == RwxCliCommands.Command.ORDER_GUARD_AT) {
            return formatReceipt(service.issue(parsePointOrder(arguments, RwxLocalGameTypes.OrderType.GUARD_AT, true)));
        }
        if (command == RwxCliCommands.Command.ORDER_FOLLOW) {
            return formatReceipt(service.issue(parseTargetOrder(arguments, RwxLocalGameTypes.OrderType.FOLLOW)));
        }
        if (command == RwxCliCommands.Command.ORDER_REPAIR) {
            return formatReceipt(service.issue(parseTargetOrder(arguments, RwxLocalGameTypes.OrderType.REPAIR)));
        }
        if (command == RwxCliCommands.Command.ORDER_RECLAIM) {
            return formatReceipt(service.issue(parseTargetOrder(arguments, RwxLocalGameTypes.OrderType.RECLAIM)));
        }
        if (command == RwxCliCommands.Command.ORDER_LOAD_INTO) {
            return formatReceipt(service.issue(parseTargetOrder(arguments, RwxLocalGameTypes.OrderType.LOAD_INTO)));
        }
        if (command == RwxCliCommands.Command.ORDER_LOAD_UP) {
            return formatReceipt(service.issue(parseTargetOrder(arguments, RwxLocalGameTypes.OrderType.LOAD_UP)));
        }
        if (command == RwxCliCommands.Command.ORDER_BUILD) {
            return formatReceipt(issue(parseBuildOrder(arguments)));
        }
        if (command == RwxCliCommands.Command.ORDER_RALLY) {
            return formatReceipt(service.issue(parsePointOrder(arguments, RwxLocalGameTypes.OrderType.RALLY, false)));
        }
        if (command == RwxCliCommands.Command.PUSH) {
            return push(arguments);
        }
        if (command == RwxCliCommands.Command.ORDER_ACTION) {
            RwxLocalGameTypes.Order order = parseActionOrder(arguments);
            if (order.repeatCount <= 1) return formatReceipt(issue(order));
            return formatRepeatedAction(order);
        }
        if (command == RwxCliCommands.Command.ORDER_CANCEL) {
            validateOptions(arguments, new String[] {"--unit", "--action", "--count"}, "--all");
            boolean all = optionalFlag(arguments, "--all");
            if (all && optionalValue(arguments, "--count") != null) {
                throw new IllegalArgumentException("Use --count or --all, not both");
            }
            int count = parseOptionalInt(arguments, "--count", 1);
            if (count < 1) throw new IllegalArgumentException("--count must be positive");
            return formatReceipt(service.cancel(parseLong("--unit", requiredOption(arguments, "--unit")),
                    optionalValue(arguments, "--action"), count, all));
        }
        if (command == RwxCliCommands.Command.ORDER_ASSIST) {
            return formatReceipt(service.assist(parseTargetOrder(
                    arguments, RwxLocalGameTypes.OrderType.REPAIR)));
        }
        if (command == RwxCliCommands.Command.ORDER_ATTACK_MODE) {
            return formatReceipt(service.issue(parseAttackModeOrder(arguments)));
        }
        if (command == RwxCliCommands.Command.MAP_SHOW) {
            return formatMap(service.map(parseMapQuery(arguments)));
        }
        if (command == RwxCliCommands.Command.MAP_REACH) {
            validateOptions(arguments, new String[] {"--unit", "--x", "--y"});
            return formatReach(service.reach(
                    parseLong("--unit", requiredOption(arguments, "--unit")),
                    parseFloat("--x", requiredOption(arguments, "--x")),
                    parseFloat("--y", requiredOption(arguments, "--y"))));
        }
        if (command == RwxCliCommands.Command.MAP_PLACE) {
            validateOptions(arguments, new String[] {"--builder", "--type", "--x", "--y", "--radius"});
            int searchRadius = parseOptionalInt(arguments, "--radius", 2);
            if (searchRadius < 0 || searchRadius > 10) {
                throw new IllegalArgumentException("--radius must be an integer from 0 to 10");
            }
            return formatPlacement(service.canPlace(
                    parseLong("--builder", requiredOption(arguments, "--builder")),
                    requiredOption(arguments, "--type"),
                    parseFloat("--x", requiredOption(arguments, "--x")),
                    parseFloat("--y", requiredOption(arguments, "--y")),
                    searchRadius));
        }
        if (command == RwxCliCommands.Command.EVENTS) {
            validateOptions(arguments, new String[] {"--types", "--seconds"}, "--allies");
            String seconds = optionalValue(arguments, "--seconds");
            RwxLocalGameTypes.EventQuery query = new RwxLocalGameTypes.EventQuery(
                    parseEventTypes(optionalValue(arguments, "--types"), "--types"),
                    seconds == null ? 30 : parseDouble("--seconds", seconds), optionalFlag(arguments, "--allies"));
            return String.join("\n", service.events(query).lines);
        }

        if (command == RwxCliCommands.Command.THREATS) {
            validateOptions(arguments, new String[] {"--x", "--y", "--radius", "--cluster-radius"},
                    "--cluster", "--json");
            String rawRadius = optionalValue(arguments, "--radius");
            float radius = parseFloat("--radius", rawRadius == null ? "300" : rawRadius);
            if (radius <= 0.0f) throw new IllegalArgumentException("--radius must be a positive number");
            boolean cluster = optionalFlag(arguments, "--cluster");
            String rawClusterRadius = optionalValue(arguments, "--cluster-radius");
            float clusterRadius = parseFloat("--cluster-radius",
                    rawClusterRadius == null ? "120" : rawClusterRadius);
            if (clusterRadius <= 0.0f) {
                throw new IllegalArgumentException("--cluster-radius must be a positive number");
            }
            return formatThreats(service.threats(new RwxLocalGameTypes.ThreatQuery(
                    parseFloat("--x", requiredOption(arguments, "--x")),
                    parseFloat("--y", requiredOption(arguments, "--y")),
                    radius, cluster, clusterRadius)), optionalFlag(arguments, "--json"));
        }
        if (command == RwxCliCommands.Command.REPLAY_START) {
            validateOptions(arguments, new String[] {"--name"});
            return formatReplay(service.startReplay(optionalValue(arguments, "--name")));
        }
        if (command == RwxCliCommands.Command.REPLAY_STOP) {
            validateOptions(arguments, new String[0]);
            return formatReplay(service.stopReplay());
        }
        if (command == RwxCliCommands.Command.REPLAY_STATUS) {
            validateOptions(arguments, new String[0]);
            return formatReplay(service.replayStatus());
        }
        if (command == RwxCliCommands.Command.RESTART) {
            requireNoArguments(arguments);
            return service.restart();
        }
        if (command == RwxCliCommands.Command.SURRENDER) {
            requireNoArguments(arguments);
            return service.surrender();
        }
        if (command == RwxCliCommands.Command.STEP) {
            validateOptions(arguments, new String[] {"--seconds", "--timeout-ms"});
            String rawSeconds = optionalValue(arguments, "--seconds");
            double seconds = rawSeconds == null ? 5.0 : parseDouble("--seconds", rawSeconds);
            if (seconds <= 0.0 || seconds > 1800.0) {
                throw new IllegalArgumentException("--seconds must be between 0.1 and 1800");
            }
            int defaultTimeout = (int) Math.min(600_000L, Math.max(5_000L, Math.round(seconds * 2000.0)));
            return formatStep(service.step(seconds,
                    parseOptionalInt(arguments, "--timeout-ms", defaultTimeout)));
        }
        if (command == RwxCliCommands.Command.SNAPSHOT) {
            validateOptions(arguments, new String[] {"--x", "--y", "--zoom", "--zoom-by"},
                    "--center", "--top-left");
            String rawX = optionalValue(arguments, "--x");
            String rawY = optionalValue(arguments, "--y");
            if ((rawX == null) != (rawY == null)) {
                throw new IllegalArgumentException("--x and --y must be given together");
            }
            boolean toCenter = optionalFlag(arguments, "--center");
            boolean toTopLeft = optionalFlag(arguments, "--top-left");
            if (toCenter && toTopLeft) {
                throw new IllegalArgumentException("Use either --center or --top-left, not both");
            }
            String rawZoom = optionalValue(arguments, "--zoom");
            String rawZoomBy = optionalValue(arguments, "--zoom-by");
            if (rawZoom != null && rawZoomBy != null) {
                throw new IllegalArgumentException("Use either --zoom <value> or --zoom-by <factor>");
            }
            Float zoom = null;
            if (rawZoom != null) {
                float value = parseFloat("--zoom", rawZoom);
                if (value <= 0.0f) throw new IllegalArgumentException("--zoom must be positive");
                zoom = value;
            } else if (rawZoomBy != null) {
                float factor = parseFloat("--zoom-by", rawZoomBy);
                if (factor <= 0.0f) throw new IllegalArgumentException("--zoom-by must be positive");
                zoom = service.camera().zoom * factor;
            }
            if (rawX == null && zoom == null) {
                if (toCenter || toTopLeft) {
                    throw new IllegalArgumentException("--center / --top-left need --x and --y");
                }
                return formatCamera(service.camera());
            }
            Float x = rawX == null ? null : (float) parseDouble("--x", rawX);
            Float y = rawY == null ? null : (float) parseDouble("--y", rawY);
            if (x != null && !toTopLeft) toCenter = true;
            return formatCamera(service.moveCamera(
                    toCenter ? x : null, toCenter ? y : null,
                    toTopLeft ? x : null, toTopLeft ? y : null, zoom));
        }

        if (command == RwxCliCommands.Command.NUKE_REPORT) {
            validateOptions(arguments, new String[] {"--unit", "--x", "--y"});
            String x = optionalValue(arguments, "--x");
            String y = optionalValue(arguments, "--y");
            if ((x == null) != (y == null)) {
                throw new IllegalArgumentException("Specify --x and --y together");
            }
            return service.nukeReport(parseLong("--unit", requiredOption(arguments, "--unit")),
                    x == null ? null : parseFloat("--x", x),
                    y == null ? null : parseFloat("--y", y));
        }
        if (command == RwxCliCommands.Command.GROUP_SAVE) {
            validateOptions(arguments,
                    new String[] {"--name", "--units", "--exclude", "--type", "--hp"},
                    "--idle", "--exclude-builders");
            return formatGroup(service.saveGroup(
                    requiredOption(arguments, "--name"), requiredOption(arguments, "--units"),
                    excludedIds(arguments), includedTypes(arguments), excludedTypeNames(arguments),
                    hpBelow(arguments), optionalFlag(arguments, "--idle"),
                    optionalFlag(arguments, "--exclude-builders")));
        }
        if (command == RwxCliCommands.Command.GROUP_LIST) {
            requireNoArguments(arguments);
            return formatGroups(service.groups());
        }
        if (command == RwxCliCommands.Command.GROUP_SHOW) {
            validateOptions(arguments, new String[] {"--name"});
            String name = requiredOption(arguments, "--name");
            RwxLocalGameTypes.GroupSnapshot found = findGroup(service.groups(), name);
            if (found == null) {
                throw new IllegalArgumentException("No saved group named '" + name
                        + "'; list them with " + RwxCliCommands.Command.GROUP_LIST.commandName());
            }
            return formatGroup(found);
        }
        if (command == RwxCliCommands.Command.GROUP_DELETE) {
            validateOptions(arguments, new String[] {"--name"});
            RwxLocalGameTypes.GroupSnapshot removed = service.deleteGroup(requiredOption(arguments, "--name"));
            return "deleted group: " + removed.name + " (removed " + removed.unitIds.size() + " saved ids)";
        }
        if (command == RwxCliCommands.Command.OBSERVE_RESET) {
            validateOptions(arguments, new String[0], "--keep-groups");
            return formatObserveReset(service.observeReset(optionalFlag(arguments, "--keep-groups")));
        }
        if (command == RwxCliCommands.Command.PENDING_LIST) {
            requireNoArguments(arguments);
            return formatPending(service.pending());
        }
        if (command == RwxCliCommands.Command.PENDING_CLEAR) {
            validateOptions(arguments, new String[] {"--id"});
            String rawId = optionalValue(arguments, "--id");
            return formatPending(service.clearPending(rawId == null ? null : parseLong("--id", rawId)));
        }
        if (command == RwxCliCommands.Command.KEEP) {
            validateOptions(arguments, new String[] {"--unit", "--action", "--count"});
            long unitId = parseLong("--unit", requiredOption(arguments, "--unit"));
            String action = requiredOption(arguments, "--action");
            int count = parseOptionalInt(arguments, "--count", 1);
            RwxLocalGameTypes.KeepPage page = service.keep(unitId, action, count);
            return formatKeep(page);
        }
        if (command == RwxCliCommands.Command.KEEP_LIST) {
            requireNoArguments(arguments);
            return formatKeep(service.keeps());
        }
        if (command == RwxCliCommands.Command.KEEP_CLEAR) {
            validateOptions(arguments, new String[] {"--id"});
            String rawId = optionalValue(arguments, "--id");
            return formatKeep(service.clearKeep(rawId == null ? null : parseLong("--id", rawId)));
        }
        if (command == RwxCliCommands.Command.AUTO_SET) {
            validateOptions(arguments, AUTO_SET_VALUE_OPTIONS, AUTO_SET_FLAGS);
            return formatAutoRules(service.autoSet(autoRuleOptions(arguments)));
        }
        if (command == RwxCliCommands.Command.AUTO_LIST) {
            requireNoArguments(arguments);
            return formatAutoRules(service.autoRules());
        }
        if (command == RwxCliCommands.Command.AUTO_SHOW) {
            validateOptions(arguments, new String[] {"--rule"});
            return formatAutoRule(service.autoShow(requiredOption(arguments, "--rule")));
        }
        if (command == RwxCliCommands.Command.AUTO_CLEAR) {
            validateOptions(arguments, new String[] {"--rule"});
            return formatAutoRule(service.autoClear(requiredOption(arguments, "--rule")));
        }
        if (command == RwxCliCommands.Command.AUTO_RESET) {
            requireNoArguments(arguments);
            return formatAutoRules(service.autoReset());
        }
        if (RwxCliCommands.isInDomain(command, RwxCliCommands.Domain.LOCAL_GAME)) {
            return "error: command is designed but not implemented yet: " + command.commandName();
        }
        return "error: unknown local-game command";
    }

    private static RwxLocalGameTypes.GroupSnapshot findGroup(
            List<RwxLocalGameTypes.GroupSnapshot> groups, String name) {
        for (RwxLocalGameTypes.GroupSnapshot group : groups) {
            if (group.name.equals(name)) return group;
        }
        return null;
    }

    private static String formatGroups(List<RwxLocalGameTypes.GroupSnapshot> groups) {
        if (groups.isEmpty()) {
            return "编组列表\n（还没有保存的编组；用 " + RwxCliCommands.Command.GROUP_SAVE.commandName()
                    + " --name main --units <ids|选择器> 保存一个）";
        }
        StringBuilder output = new StringBuilder("编组列表 (").append(groups.size()).append("):");
        for (RwxLocalGameTypes.GroupSnapshot group : groups) {
            output.append("\n").append(group.name).append(": ")
                    .append(group.liveCount).append(" 存活 / ").append(group.unitIds.size()).append(" 已保存")
                    .append(" — ").append(formatComposition(group.composition))
                    .append("\n    ids: ").append(joinIdsShort(group.unitIds, 12));
        }
        output.append("\n用法: --units group:<name>");
        return output.toString();
    }

    private static String formatGroup(RwxLocalGameTypes.GroupSnapshot group) {
        StringBuilder output = new StringBuilder("编组: ").append(group.name)
                .append("\n组成: ").append(formatComposition(group.composition))
                .append("\nunits: ").append(RwxCliText.joinIds(group.unitIds))
                .append("\ncurrently alive: ").append(group.liveCount)
                .append("\nsaved ids: ").append(group.unitIds.size());
        int dead = group.unitIds.size() - group.liveCount;
        if (dead > 0) {
            output.append("\n已阵亡: ").append(dead).append(" (这些 id 仍在编组里，但已不再是存活单位)");
        }
        output.append("\n用法: order-* --units group:").append(group.name);
        return output.toString();
    }

    /** "小型坦克 x6, 重型坦克 x4" — the readable half of a saved group. */
    private static String formatComposition(List<RwxLocalGameTypes.GroupTypeCount> composition) {
        if (composition == null || composition.isEmpty()) return "（无存活单位）";
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (RwxLocalGameTypes.GroupTypeCount item : composition) {
            if (shown >= 8) {
                text.append(", …共 ").append(composition.size()).append(" 类");
                break;
            }
            if (shown > 0) text.append(", ");
            String label = item.displayName == null || item.displayName.trim().isEmpty()
                    ? item.unitTypeId : item.displayName;
            text.append(label).append(" x").append(item.count);
            shown++;
        }
        return text.toString();
    }

    private static String joinIdsShort(List<Long> ids, int limit) {
        if (ids.size() <= limit) return RwxCliText.joinIds(ids);
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            if (index > 0) text.append(',');
            text.append(ids.get(index));
        }
        return text.append(", …(+").append(ids.size() - limit).append(')').toString();
    }

    private static String formatObserveReset(RwxLocalGameTypes.ObserveResetSnapshot reset) {
        StringBuilder output = new StringBuilder("观察账本已重置")
                .append(" (tick ").append(reset.tick)
                .append(", time ").append(RwxCliText.time(reset.gameTimeMillis)).append(')')
                .append("\ncleared: events=").append(reset.clearedEvents)
                .append(", groups=").append(reset.clearedGroups)
                .append("\nkept:");
        if (reset.keptGroups) output.append(" 编组");
        if (reset.keptDefaultStance) {
            output.append(reset.keptGroups ? "、" : " ").append("默认姿态与已下发姿态的单位记录");
        }
        output.append("\n事件历史和 unit-list 的变化基线已重置；")
                .append("事件（rwx-game-events）不会再返回重置前的记录。");
        return output.toString();
    }

    private static String formatPending(RwxLocalGameTypes.PendingPage page) {
        StringBuilder output = new StringBuilder("待发指令 (tick ").append(page.tick)
                .append(", time ").append(RwxCliText.time(page.gameTimeMillis)).append(')')
                .append("\ncount: ").append(page.orders.size())
                .append("\n说明: 这些是 --allow-unaffordable / --after-upgrade 暂存的指令；"
                        + "条件满足时由后台采样器自动发出（资金 / 升级完成）。");
        if (page.orders.isEmpty()) {
            output.append("当前没有待发项。");
        } else {
            output.append("发出后会从列表消失，见下面的“最近动作”。");
            for (RwxLocalGameTypes.PendingOrderSnapshot order : page.orders) {
                output.append("\n  #").append(order.id)
                        .append(" [").append(RwxCliText.time(order.createdTimeMillis)).append("] ")
                        .append(order.kind);
                if (order.actionId != null) output.append(' ').append(order.actionId);
                if (!order.unitIds.isEmpty()) output.append(" units=").append(RwxCliText.joinIds(order.unitIds));
                if (order.x != null && order.y != null) {
                    output.append(" @").append((int) (float) order.x)
                            .append(',').append((int) (float) order.y);
                }
                if (order.targetUnitId != null) output.append(" target=").append(order.targetUnitId);
                output.append("\n     等待: ").append("upgrade".equals(order.waitFor) ? "升级完成" : "资金")
                        .append(" | 原因: ").append(order.reason);
            }
        }
        if (!page.recent.isEmpty()) {
            output.append("\n最近动作（新→旧）:");
            for (String note : page.recent) output.append("\n  · ").append(note);
        }
        return output.toString();
    }

    private static String formatStep(RwxLocalGameTypes.StepSnapshot step) {
        RwxLocalGameTypes.GameSnapshot game = step.game;
        StringBuilder output = new StringBuilder("已推进模拟")
                .append("\nrequested: ").append(step.requestedSeconds).append(" s")
                .append("\nadvanced: ").append(String.format(Locale.ROOT, "%.1f", step.advancedSeconds)).append(" s")
                .append("\nwasPaused: ").append(step.wasPaused)
                .append("\ntimedOut: ").append(step.timedOut)
                .append("\nstalled: ").append(step.stalled);
        if (step.stalled) {
            output.append(" (游戏线程未在 ")
                    .append(300)
                    .append(" ms 内响应读取；已直接暂停对局，tick/time 是最后一次成功的快照)");
        } else if (step.timedOut) {
            output.append(" (被其他原因中断或墙钟超时，返回的是当前状态)");
        }
        output.append("\npaused: ").append(game.paused)
                .append("\ntick: ").append(game.tick)
                .append("\ntime: ").append(RwxCliText.time(game.gameTimeMillis))
                .append("\nspeed: ").append(game.speed);
        if (game.credits != null) output.append("\ncredits: ").append(RwxCliText.number(game.credits));
        if (game.unitCount != null) output.append("\nunits: ").append(game.unitCount);
        output.append("\noutcome: ").append(game.outcome);
        if (!"active".equals(game.outcome)) output.append(" (对局已结束，step 在结束时停下)");
        return output.toString();
    }

    /** {@code rwx-game-camera}: the world rectangle the GUI is showing right now. */
    private static String formatCamera(RwxLocalGameTypes.CameraSnapshot view) {
        StringBuilder output = new StringBuilder("GUI 视野")
                .append("\nzoom: ").append(RwxCliText.number(view.zoom))
                .append("  (范围 ").append(RwxCliText.number(view.minZoom))
                .append("–").append(RwxCliText.number(view.maxZoom)).append(')')
                .append("\nrect: (").append(RwxCliText.number(view.left)).append(", ")
                .append(RwxCliText.number(view.top)).append(")-(")
                .append(RwxCliText.number(view.right)).append(", ")
                .append(RwxCliText.number(view.bottom)).append(')')
                .append("\ncenter: ").append(RwxCliText.number(view.centerX))
                .append(", ").append(RwxCliText.number(view.centerY))
                .append("\ntop-left: ").append(RwxCliText.number(view.left))
                .append(", ").append(RwxCliText.number(view.top))
                .append("\nsize: W ").append(RwxCliText.number(view.width()))
                .append("  H ").append(RwxCliText.number(view.height()))
                .append("  (屏幕 ").append(RwxCliText.number(view.screenWidth))
                .append("x").append(RwxCliText.number(view.screenHeight)).append(')')
                .append("\nworld: ").append(view.worldWidth).append(" x ").append(view.worldHeight);
        for (String note : view.clampNotes) output.append("\n调整: ").append(note);
        return output.toString();
    }

    private static void requireNoArguments(List<String> arguments) {
        if (!arguments.isEmpty()) {
            throw new IllegalArgumentException("this command does not accept arguments");
        }
    }

    private static String format(RwxLocalGameTypes.GameSnapshot state) {
        StringBuilder output = new StringBuilder("游戏状态\n")
                .append("tick: ").append(state.tick).append("\n")
                .append("time: ").append(RwxCliText.time(state.gameTimeMillis))
                .append(" (").append(state.gameTimeMillis).append(" ms)\n")
                .append("paused: ").append(state.paused).append("\n")
                .append("speed: ").append(state.speed).append("\n")
                .append("map: ").append(state.mapName).append("\n")
                .append("controlledTeam: ").append(state.controlledTeamId).append("\n")
                .append("outcome: ").append(state.outcome);
        if (state.credits != null) output.append("\ncredits: ").append(RwxCliText.number(state.credits));
        if (state.incomePerSecond != null) {
            output.append("\nincomePerSecond: ").append(RwxCliText.number(state.incomePerSecond));
        }
        if (state.unitCount != null) output.append("\nunits: ").append(state.unitCount);
        if (state.clockOverflowed) {
            output.append("\nclockOverflowed: true（引擎 int 时钟已越过 2^31 ms ≈ 24.8 游戏天：")
                    .append("time 从 0 重新计，观测账本已重置，窗口类命令只覆盖溢出之后的数据）");
        }
        return output.toString();
    }

    /** Pause is a state toggle; a full game-show dump is noise for scripted play. */
    private static String formatPause(RwxLocalGameTypes.GameSnapshot state) {
        return "paused: " + state.paused
                + " | tick: " + state.tick
                + " | time: " + RwxCliText.time(state.gameTimeMillis)
                + " | speed: " + state.speed
                + (state.credits == null ? "" : " | credits: " + RwxCliText.number(state.credits));
    }

    private static String formatTeams(List<RwxLocalGameTypes.TeamSnapshot> teams) {
        StringBuilder output = new StringBuilder("队伍列表");
        for (RwxLocalGameTypes.TeamSnapshot team : teams) {
            output.append("\n")
                    .append("id: ").append(team.teamId)
                    .append(" | name: ").append(team.name)
                    .append(" | relation: ").append(team.relation)
                    .append(" | color: ").append(team.teamColor)
                    .append(" | ai: ").append(team.ai)
                    .append(" | status: ").append(team.status);
            if (team.credits != null) output.append(" | credits: ").append(RwxCliText.number(team.credits));
            if (team.incomePerSecond != null) {
                output.append(" | incomePerSecond: ").append(RwxCliText.number(team.incomePerSecond));
            }
            if (team.unitCount != null) output.append(" | unitCount: ").append(team.unitCount);
        }
        return output.toString();
    }

    /** One unit object, including its queues and (for factories) available actions. */
    private static void appendUnitJson(StringBuilder output, RwxLocalGameTypes.UnitSnapshot unit,
                                       List<RwxLocalGameTypes.ActionSnapshot> actions) {
        output.append("{\"id\":").append(unit.id)
                .append(",\"type\":\"").append(RwxCliText.json(unit.unitTypeId)).append('"')
                .append(",\"name\":\"").append(RwxCliText.json(unit.displayName)).append('"')
                .append(",\"team\":").append(unit.teamId)
                .append(",\"relation\":\"").append(RwxCliText.json(unit.relation)).append('"')
                .append(",\"x\":").append(RwxCliText.number(unit.x))
                .append(",\"y\":").append(RwxCliText.number(unit.y))
                .append(",\"hp\":").append(RwxCliText.number(unit.health))
                .append(",\"maxHp\":").append(RwxCliText.number(unit.maxHealth))
                .append(",\"build\":").append(RwxCliText.number(unit.buildProgress))
                .append(",\"tech\":").append(unit.techLevel)
                .append(",\"upgrade\":").append(unit.upgradeLevel)
                .append(",\"controllable\":").append(unit.controllable)
                .append(",\"movement\":\"").append(RwxCliText.json(unit.movementType)).append('"');
        if (unit.currentOrder != null) {
            output.append(",\"order\":\"").append(RwxCliText.json(unit.currentOrder)).append('"');
        }
        if (unit.targetUnitId != null) output.append(",\"target\":").append(unit.targetUnitId);
        if (unit.queueSize != null) output.append(",\"queue\":").append(unit.queueSize);
        if (unit.rallyX != null) {
            output.append(",\"rally\":{\"x\":").append(RwxCliText.number(unit.rallyX))
                    .append(",\"y\":").append(RwxCliText.number(unit.rallyY)).append('}');
        }
        String upgrading = upgradingLabel(unit);
        if (upgrading != null) {
            output.append(",\"upgrading\":\"").append(RwxCliText.json(upgrading)).append('"');
        }
        appendQueueJson(output, "production", unit.productionQueue);
        appendQueueJson(output, "waypoints", unit.waypointQueue);
        if (actions != null) {
            output.append(",\"actions\":[");
            boolean first = true;
            for (RwxLocalGameTypes.ActionSnapshot action : actions) {
                if (!first) output.append(',');
                output.append("{\"id\":\"").append(RwxCliText.json(action.actionId)).append('"')
                        .append(",\"name\":\"").append(RwxCliText.json(action.name)).append('"')
                        .append(",\"available\":").append(action.available)
                        .append(",\"queued\":").append(action.queuedCount)
                        .append(",\"target\":\"").append(targetLabel(action)).append('"');
                if (action.price != null) {
                    output.append(",\"price\":\"").append(RwxCliText.json(action.price)).append('"');
                }
                if (action.producedUnitTypeId != null) {
                    output.append(",\"unitType\":\"")
                            .append(RwxCliText.json(action.producedUnitTypeId)).append('"');
                }
                if (action.unavailableReason != null) {
                    output.append(",\"reason\":\"")
                            .append(RwxCliText.json(action.unavailableReason)).append('"');
                }
                output.append('}');
                first = false;
            }
            output.append(']');
        }
        output.append('}');
    }

    private static void appendQueueJson(StringBuilder output, String key,
                                        List<RwxLocalGameTypes.QueueItem> queue) {
        output.append(",\"").append(key).append("\":[");
        boolean first = true;
        for (RwxLocalGameTypes.QueueItem item : queue) {
            if (!first) output.append(',');
            output.append("{\"command\":\"").append(RwxCliText.json(item.command)).append('"');
            if (item.unitTypeId != null) {
                output.append(",\"type\":\"").append(RwxCliText.json(item.unitTypeId)).append('"');
            }
            if (item.unitTypeName != null) {
                output.append(",\"name\":\"").append(RwxCliText.json(item.unitTypeName)).append('"');
            }
            if (item.x != null) {
                output.append(",\"x\":").append(RwxCliText.number(item.x))
                        .append(",\"y\":").append(RwxCliText.number(item.y));
            }
            if (item.targetUnitId != null) {
                output.append(",\"target\":").append(item.targetUnitId);
            }
            if (item.progress != null) {
                output.append(",\"progress\":").append(RwxCliText.number(item.progress));
            }
            output.append('}');
            first = false;
        }
        output.append(']');
    }


    private static RwxLocalGameTypes.UnitQuery parseUnitQuery(List<String> arguments) {
        return parseUnitQuery(arguments, false, false);
    }

    private static RwxLocalGameTypes.UnitQuery parseUnitQuery(List<String> arguments,
                                                              boolean readOnly) {
        return parseUnitQuery(arguments, readOnly, false);
    }

    /**
     * @param readOnly do not advance the change baseline (JSON / summary reads)
     * @param ignoreHp list every entity, ignoring the default 80% health filter
     */
    private static RwxLocalGameTypes.UnitQuery parseUnitQuery(List<String> arguments,
                                                              boolean readOnly,
                                                              boolean ignoreHp) {
        validateOptions(arguments,
                new String[] {"--owner", "--type", "--x", "--y", "--radius", "--group", "--units",
                        "--exclude", "--hp"},
                "--idle", "--exclude-builders", "--full", "--delta", "--compact", "--json",
                "--summary");
        String owner = optionalValue(arguments, "--owner");
        String type = optionalValue(arguments, "--type");
        String x = optionalValue(arguments, "--x");
        String y = optionalValue(arguments, "--y");
        String radius = optionalValue(arguments, "--radius");
        if ((x == null) != (y == null)) throw new IllegalArgumentException("Specify --x and --y together");
        if (radius != null && x == null) throw new IllegalArgumentException("--radius requires --x and --y");
        Float parsedRadius = radius == null ? null : parseFloat("--radius", radius);
        if (parsedRadius != null && parsedRadius < 0.0f) {
            throw new IllegalArgumentException("--radius must be zero or positive");
        }
        List<String> types = new ArrayList<>();
        if (type != null) {
            for (String token : type.split(",")) {
                if (!token.trim().isEmpty()) types.add(token.trim());
            }
        }
        // The list is a "what needs attention" view by default: only entities under 80% health.
        // --hp moves that line, --full drops it entirely; --summary counts everything.
        boolean full = optionalFlag(arguments, "--full");
        if (full && optionalValue(arguments, "--hp") != null) {
            throw new IllegalArgumentException("--full and --hp cannot be combined");
        }
        Float hpLimit = ignoreHp || full ? null : hpBelow(arguments, UNIT_LIST_DEFAULT_HP);
        return new RwxLocalGameTypes.UnitQuery(
                parseOwner(owner), types, x == null ? null : parseFloat("--x", x),
                y == null ? null : parseFloat("--y", y), parsedRadius,
                optionalFlag(arguments, "--idle"),
                optionalValue(arguments, "--group"), readOnly,
                optionalFlag(arguments, "--exclude-builders"),
                optionalValue(arguments, "--units"), types,
                excludedTypeNames(arguments), hpLimit);
    }

    private static RwxLocalGameTypes.UnitOwner parseOwner(String owner) {
        if (owner == null) return null;
        String normalized = owner.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "self":
                return RwxLocalGameTypes.UnitOwner.SELF;
            case "ally":
            case "allies":
                return RwxLocalGameTypes.UnitOwner.ALLY;
            case "enemy":
            case "enemies":
            case "enemy-visible":
                return RwxLocalGameTypes.UnitOwner.ENEMY;
            case "neutral":
                return RwxLocalGameTypes.UnitOwner.NEUTRAL;
            default:
                throw new IllegalArgumentException("--owner must be self, ally, enemy or neutral");
        }
    }

    private static RwxLocalGameTypes.MapQuery parseMapQuery(List<String> arguments) {
        validateOptions(arguments,
                new String[] {"--x", "--y", "--half-width", "--half-height"},
                "--fog", "--spawns", "--terrain", "--ascii");
        boolean terrain = optionalFlag(arguments, "--terrain");
        boolean ascii = optionalFlag(arguments, "--ascii");
        if (ascii && !terrain) {
            throw new IllegalArgumentException("--ascii requires --terrain");
        }
        String rawX = optionalValue(arguments, "--x");
        String rawY = optionalValue(arguments, "--y");
        if ((rawX == null) != (rawY == null)) {
            throw new IllegalArgumentException("--x and --y must be given together");
        }
        Float x = rawX == null ? null : parseFloat("--x", rawX);
        Float y = rawY == null ? null : parseFloat("--y", rawY);
        Integer halfWidth = optionalIntegerArgument(arguments, "--half-width");
        Integer halfHeight = optionalIntegerArgument(arguments, "--half-height");
        if ((halfWidth == null) != (halfHeight == null)) {
            throw new IllegalArgumentException("--half-width and --half-height must be given together");
        }
        if (!terrain && (halfWidth != null || x != null)) {
            throw new IllegalArgumentException(
                    "--x/--y/--half-width/--half-height only apply together with --terrain");
        }
        int halfWidthTiles = halfWidth == null ? 8 : halfWidth;
        int halfHeightTiles = halfHeight == null ? 8 : halfHeight;
        if (halfWidth != null) {
            if (halfWidthTiles < 1 || halfWidthTiles > 40) {
                throw new IllegalArgumentException("--half-width must be an integer from 1 to 40 (tiles)");
            }
            if (halfHeightTiles < 1 || halfHeightTiles > 40) {
                throw new IllegalArgumentException("--half-height must be an integer from 1 to 40 (tiles)");
            }
            if ((halfWidthTiles * 2L + 1) * (halfHeightTiles * 2L + 1) > 8000L) {
                throw new IllegalArgumentException(
                        "Terrain window is too large (max 8000 cells); shrink --half-width/--half-height");
            }
        }
        return new RwxLocalGameTypes.MapQuery(
                optionalFlag(arguments, "--fog"), optionalFlag(arguments, "--spawns"),
                terrain, x, y, halfWidthTiles, halfHeightTiles, ascii);
    }

    private static String formatMap(RwxLocalGameTypes.MapSnapshot map) {
        StringBuilder output = new StringBuilder("map(WxH/")
                .append(map.width).append('x').append(map.height).append(",world unit):")
                .append("\n1 tile=").append(RwxCliText.number(map.tileWidth));
        if (map.tileHeight != map.tileWidth) {
            output.append('x').append(RwxCliText.number(map.tileHeight));
        }
        output.append(" world units");
        if (map.terrain != null) appendTerrainRange(output, map.terrain);
        output.append("\nresourcePools: ").append(map.resourcePools.size());
        for (RwxLocalGameTypes.ResourcePool pool : map.resourcePools) {
            output.append("\n  · ").append(RwxCliText.number(pool.x)).append(',')
                    .append(RwxCliText.number(pool.y)).append(" | ")
                    .append(pool.occupied() ? "8 (occupied)" : "0 (unoccupied)");
            if (pool.occupied()) {
                output.append(" | team ").append(pool.occupantTeamId)
                        .append(' ').append(pool.occupantType).append(" #").append(pool.occupantId);
            }
        }
        if (!map.spawns.isEmpty()) {
            output.append("\nspawns: ").append(map.spawns.size());
            for (RwxLocalGameTypes.TeamSpawn spawn : map.spawns) {
                output.append("\n  · team ").append(spawn.teamId)
                        .append(' ').append(relationLabel(spawn.relation));
                if (spawn.teamName != null && !spawn.teamName.trim().isEmpty()) {
                    output.append(" \"").append(spawn.teamName).append('"');
                }
                if (spawn.x < 0.0f) {
                    output.append(" | 位置未知（该队基地尚未侦察到）");
                    continue;
                }
                output.append(" | ").append((int) spawn.x).append(',').append((int) spawn.y);
                if (spawn.buildingId != null) {
                    output.append(" | ").append(spawn.buildingTypeId).append(" id=").append(spawn.buildingId);
                } else {
                    output.append(" | 首次出现位置（基地不在视野内，近似值）");
                }
            }
        }
        if (map.terrain != null) appendTerrain(output, map.terrain);
        return output.toString();
    }

    private static void appendTerrainRange(StringBuilder output, RwxLocalGameTypes.TerrainWindow terrain) {
        int lastX = terrain.tileX + terrain.widthTiles - 1;
        int lastY = terrain.tileY + terrain.heightTiles - 1;
        output.append("\n  range: x=").append(RwxCliText.number(terrain.worldX))
                .append("..").append(RwxCliText.number(terrain.worldX + terrain.widthTiles * terrain.tileWidth))
                .append(" y=").append(RwxCliText.number(terrain.worldY))
                .append("..").append(RwxCliText.number(terrain.worldY + terrain.heightTiles * terrain.tileHeight))
                .append("(world unit) x=").append(terrain.tileX).append("..").append(lastX)
                .append(" y=").append(terrain.tileY).append("..").append(lastY).append("(tiles)")
                .append("\n  scale: ").append(RwxCliText.number(terrain.widthTiles * terrain.tileWidth))
                .append('x').append(RwxCliText.number(terrain.heightTiles * terrain.tileHeight))
                .append("(world unit) ").append(terrain.widthTiles).append('x').append(terrain.heightTiles)
                .append("(tile)");
        if (terrain.clipped) output.append("\n  note: 超出地图边界，已裁剪");
    }

    /** Numeric categories by default; --ascii uses space-separated category characters. */
    private static void appendTerrain(StringBuilder output, RwxLocalGameTypes.TerrainWindow terrain) {
        String[][] cells = new String[terrain.heightTiles][terrain.widthTiles];
        int width = terrain.asciiRequested ? 1 : 2;
        for (int y = 0; y < terrain.heightTiles; y++) {
            for (int x = 0; x < terrain.widthTiles; x++) {
                String building = terrain.buildingLabels == null ? null : terrain.buildingLabels[y][x];
                String cell = terrain.asciiRequested ? String.valueOf(RwxTerrainCodes.symbol(terrain.codes[y][x]))
                        : building != null ? building : Integer.toString(terrain.codes[y][x]);
                cells[y][x] = cell;
                width = Math.max(width, cell.length());
            }
        }
        output.append(terrain.asciiRequested
                ? "\n  legend: .=land ~=water ^=mountain *=lava 0=free-resource 8=occupied-resource ?=fog"
                : "\n  legend: 1=land -1=water -7=mountain -9=lava 0=free-resource 8=occupied-resource -2=fog #ID=our-building 4=allied-building -4=enemy-building");
        output.append("\n  grid (row = y, col = x):");
        int rowWidth = Integer.toString(terrain.tileY + terrain.heightTiles - 1).length();
        for (int y = 0; y < terrain.heightTiles; y++) {
            String row = Integer.toString(terrain.tileY + y);
            output.append("\n  y=").append(" ".repeat(rowWidth - row.length())).append(row).append(" |");
            for (String cell : cells[y]) output.append(' ').append(" ".repeat(width - cell.length())).append(cell);
        }
    }

    private static String formatPlacement(RwxLocalGameTypes.PlacementSnapshot placement) {
        StringBuilder output = new StringBuilder("可放置: ").append(placement.placeable)
                .append("\n落点: ").append((int) placement.snappedX).append(',').append((int) placement.snappedY)
                .append("\nsearchRadius: ").append(placement.searchRadiusTiles).append(" tiles")
                .append("\nbuilderReach: ").append(placement.builderReachable)
                .append("\nreason: ").append(placement.reason);
        if (!placement.placeable) {
            if (placement.searchRadiusTiles == 0) {
                output.append("\n最近可放: 未搜索（--radius 0）");
            } else if (placement.suggestions.isEmpty()) {
                output.append("\n最近可放: 未找到（搜索半径 ")
                        .append(placement.searchRadiusTiles).append(" 格内）");
            } else {
                output.append("\n最近可放（搜索半径 ").append(placement.searchRadiusTiles).append(" 格内）:");
                int index = 1;
                for (RwxLocalGameTypes.PlacementSuggestion suggestion : placement.suggestions) {
                    output.append("\n  ").append(index++).append(". ")
                            .append((int) suggestion.x).append(',').append((int) suggestion.y)
                            .append(" | offset: ").append((int) suggestion.distance).append(" world")
                            .append(" | 落点: ").append((int) suggestion.snappedX)
                            .append(',').append((int) suggestion.snappedY);
                }
            }
        }
        return output.toString();
    }

    private static String formatReach(RwxLocalGameTypes.ReachabilitySnapshot reach) {
        StringBuilder output = new StringBuilder("可达性\n")
                .append("reachable: ").append(reach.reachable)
                .append("\nmovement: ").append(reach.movementType)
                .append("\nstartTile: ").append(reach.startTileX).append(',').append(reach.startTileY)
                .append("\ngoalTile: ").append(reach.goalTileX).append(',').append(reach.goalTileY)
                .append(" | goal: ").append((int) reach.goalX).append(',').append((int) reach.goalY)
                .append(" (world)");
        if (reach.goalSnapped) output.append(" (snapped to nearest passable tile)");
        output.append("\nstraightReachable: ").append(reach.straightReachable)
                .append("\nstraightBlockedTiles: ").append(reach.straightBlockedTiles);
        if (!reach.straightBlockers.isEmpty()) {
            output.append("\nstraightBlockers: ").append(String.join(", ", reach.straightBlockers));
        }
        if (reach.straightBlockage != null) {
            output.append("\nstraightBlockage: ").append(reach.straightBlockage);
        }
        output.append("\nreason: ").append(reach.reason);
        if (reach.reachable) {
            output.append("\nstraightDistance: ").append((int) reach.straightDistance).append(" world")
                    .append("\npathDistance: ").append((int) reach.pathDistance).append(" world")
                    .append("\ndetourRatio: ").append(String.format(Locale.ROOT, "%.2f", reach.detourRatio))
                    .append("x the Manhattan minimum (")
                    .append(Math.round((reach.detourRatio - 1.0f) * 100.0f))
                    .append("% extra travel)")
                    .append("\npathTiles: ").append(reach.pathTiles).append(" tiles")
                    .append("\nwaypoints (").append(reach.waypoints.size()).append("):");
            for (RwxLocalGameTypes.Point point : reach.waypoints) {
                output.append("\n  · ").append((int) point.x).append(',').append((int) point.y);
            }
            if (reach.detourRatio >= 1.35f) {
                output.append("\n注意: 需要明显绕路（直线距离 ").append((int) reach.straightDistance)
                        .append("，路径 ").append((int) reach.pathDistance)
                        .append("），可能是高地/水域/岩浆阻隔。");
            }
        } else {
            output.append("\n结论: 完全不可达");
            if (reach.straightBlockage != null) {
                output.append("；直线方向有").append(reach.straightBlockage);
            }
        }
        return output.toString();
    }

    private static RwxLocalGameTypes.Order parsePointOrder(List<String> arguments,
                                                            RwxLocalGameTypes.OrderType type,
                                                            boolean queueSupported) {
        validateOptions(arguments, new String[] {"--units", "--x", "--y", "--exclude", "--type", "--hp"},
                queueSupported ? new String[] {"--queue", "--idle", "--exclude-builders"}
                        : new String[] {"--idle", "--exclude-builders"});
        UnitSelection selection = parseUnitSelection(arguments);
        return withSelectionFilters(new RwxLocalGameTypes.Order(
                type, selection.ids, selection.selector, null, null, null, null,
                parseFloat("--x", requiredOption(arguments, "--x")),
                parseFloat("--y", requiredOption(arguments, "--y")),
                queueSupported && optionalFlag(arguments, "--queue"), false,
                excludedIds(arguments), 1, false, 0), arguments);
    }

    private static RwxLocalGameTypes.Order parseTargetOrder(List<String> arguments,
                                                             RwxLocalGameTypes.OrderType type) {
        if (type == RwxLocalGameTypes.OrderType.RECLAIM) {
            validateOptions(arguments, new String[] {"--units", "--target", "--exclude", "--type", "--hp"},
                    "--queue", "--force", "--idle", "--exclude-builders");
        } else {
            validateOptions(arguments, new String[] {"--units", "--target", "--exclude", "--type", "--hp"},
                    "--queue", "--idle", "--exclude-builders");
        }
        UnitSelection selection = parseUnitSelection(arguments);
        return withSelectionFilters(new RwxLocalGameTypes.Order(
                type, selection.ids, selection.selector,
                parseLong("--target", requiredOption(arguments, "--target")),
                null, null, null, null, null, optionalFlag(arguments, "--queue"), false,
                excludedIds(arguments), 1, false, 0, optionalFlag(arguments, "--force")), arguments);
    }

    private static RwxLocalGameTypes.Order parseStopOrder(List<String> arguments) {
        validateOptions(arguments, new String[] {"--units", "--exclude", "--type", "--hp"},
                "--idle", "--exclude-builders");
        UnitSelection selection = parseUnitSelection(arguments);
        return withSelectionFilters(new RwxLocalGameTypes.Order(
                RwxLocalGameTypes.OrderType.STOP, selection.ids, selection.selector,
                null, null, null, null, null, null, false, false,
                excludedIds(arguments), 1, false, 0), arguments);
    }

    private static RwxLocalGameTypes.Order parseBuildOrder(List<String> arguments) {
        validateOptions(arguments,
                new String[] {"--builder", "--type", "--x", "--y", "--count", "--radius", "--exclude"},
                "--queue", "--auto-place", "--allow-unaffordable");
        List<Long> builders = java.util.Collections.singletonList(
                parseLong("--builder", requiredOption(arguments, "--builder")));
        String rawRadius = optionalValue(arguments, "--radius");
        int radius = 2;
        if (rawRadius != null) {
            long parsed = parseLong("--radius", rawRadius);
            if (parsed < 0 || parsed > 10) {
                throw new IllegalArgumentException("--radius must be an integer from 0 to 10");
            }
            radius = (int) parsed;
        }
        return new RwxLocalGameTypes.Order(
                RwxLocalGameTypes.OrderType.BUILD, builders, null, null,
                requiredOption(arguments, "--type"), null, null,
                parseFloat("--x", requiredOption(arguments, "--x")),
                parseFloat("--y", requiredOption(arguments, "--y")), optionalFlag(arguments, "--queue"), false,
                excludedIds(arguments), repeatCount(arguments, 20),
                optionalFlag(arguments, "--auto-place"), radius, false, false, false,
                java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList(),
                null, optionalFlag(arguments, "--allow-unaffordable"));
    }

    private static RwxLocalGameTypes.Order parseActionOrder(List<String> arguments) {
        validateOptions(arguments, new String[] {"--units", "--action", "--target", "--x", "--y",
                "--count", "--exclude", "--type", "--hp"}, "--idle", "--exclude-builders",
                "--allow-unaffordable", "--after-upgrade");
        String target = optionalValue(arguments, "--target");
        String x = optionalValue(arguments, "--x");
        String y = optionalValue(arguments, "--y");
        if (target != null && (x != null || y != null)) {
            throw new IllegalArgumentException("Specify either --target or --x and --y for an action");
        }
        if ((x == null) != (y == null)) throw new IllegalArgumentException("Specify --x and --y together");
        UnitSelection selection = parseUnitSelection(arguments);
        return withSelectionFilters(new RwxLocalGameTypes.Order(
                RwxLocalGameTypes.OrderType.ACTION, selection.ids, selection.selector,
                target == null ? null : parseLong("--target", target), null,
                requiredOption(arguments, "--action"), null,
                x == null ? null : parseFloat("--x", x), y == null ? null : parseFloat("--y", y),
                false, false, excludedIds(arguments), repeatCount(arguments, 20), false, 0), arguments);
    }

    private static RwxLocalGameTypes.Order parseAttackModeOrder(List<String> arguments) {
        validateOptions(arguments, new String[] {"--units", "--mode", "--exclude", "--type", "--hp"},
                "--apply-to-new", "--idle", "--exclude-builders");
        boolean applyToNew = optionalFlag(arguments, "--apply-to-new");
        String mode = requiredOption(arguments, "--mode");
        boolean clear = "off".equalsIgnoreCase(mode) || "none".equalsIgnoreCase(mode)
                || "clear".equalsIgnoreCase(mode);
        List<Long> ids = new ArrayList<>();
        String selector = null;
        if (optionalValue(arguments, "--units") != null) {
            UnitSelection selection = parseUnitSelection(arguments);
            ids = selection.ids;
            selector = selection.selector;
        } else if (!applyToNew && !clear) {
            throw new IllegalArgumentException("--units is required unless --apply-to-new is used");
        }
        return withSelectionFilters(new RwxLocalGameTypes.Order(
                RwxLocalGameTypes.OrderType.ATTACK_MODE, ids, selector, null,
                null, null, mode, null, null, false, applyToNew,
                excludedIds(arguments), 1, false, 0), arguments);
    }

    private static RwxLocalGameTypes.Order withSelectionFilters(RwxLocalGameTypes.Order order,
                                                                 List<String> arguments) {
        return new RwxLocalGameTypes.Order(order.type, order.unitIds, order.unitSelector,
                order.targetUnitId, order.unitTypeId, order.actionId, order.attackMode,
                order.x, order.y, order.queued, order.applyToNew, order.excludedUnitIds,
                order.repeatCount, order.autoPlace, order.placementRadius, order.force,
                optionalFlag(arguments, "--idle"), optionalFlag(arguments, "--exclude-builders"),
                includedTypes(arguments), excludedTypeNames(arguments), hpBelow(arguments),
                optionalFlag(arguments, "--allow-unaffordable"),
                optionalFlag(arguments, "--after-upgrade"));
    }

    /** {@code --exclude}: numeric entries are ids; other entries are unit type names. */
    private static List<Long> excludedIds(List<String> arguments) {
        List<Long> ids = new ArrayList<>();
        for (String value : optionTokens(arguments, "--exclude")) {
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException notId) {
                // A name token filters by unit type (see excludedTypeNames); keep this list ids-only.
            }
        }
        return ids;
    }

    /** Non-numeric {@code --exclude} entries: unit type ids/names to drop from the selection. */
    private static List<String> excludedTypeNames(List<String> arguments) {
        List<String> names = new ArrayList<>();
        for (String value : optionTokens(arguments, "--exclude")) {
            try {
                Long.parseLong(value);
            } catch (NumberFormatException notId) {
                names.add(value);
            }
        }
        return names;
    }

    /** {@code --type}: keep only these unit types (internal id or display name). */
    private static List<String> includedTypes(List<String> arguments) {
        return optionTokens(arguments, "--type");
    }

    /** {@code --hp xx}: keep only units below xx percent of max health. */
    private static Float hpBelow(List<String> arguments) {
        String raw = optionalValue(arguments, "--hp");
        if (raw == null) return null;
        float value = parseFloat("--hp", raw);
        if (value <= 0.0f || value > 100.0f) {
            throw new IllegalArgumentException("--hp must be a percentage from 1 to 100");
        }
        return value;
    }

    /** Same filter, with the caller's default when {@code --hp} is absent. */
    private static Float hpBelow(List<String> arguments, float fallback) {
        Float explicit = hpBelow(arguments);
        return explicit == null ? Float.valueOf(fallback) : explicit;
    }

    private static List<String> optionTokens(List<String> arguments, String flag) {
        String raw = optionalValue(arguments, flag);
        if (raw == null) return java.util.Collections.emptyList();
        List<String> tokens = new ArrayList<>();
        for (String token : raw.split(",", -1)) {
            String value = token.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(flag + " must be a comma-separated list");
            }
            tokens.add(value);
        }
        return tokens;
    }

    /** {@code --count N}: repeat a command N times (production/action batches). */
    private static int repeatCount(List<String> arguments, int max) {
        String raw = optionalValue(arguments, "--count");
        if (raw == null) return 1;
        long count = parseLong("--count", raw);
        if (count < 1 || count > max) {
            throw new IllegalArgumentException("--count must be an integer from 1 to " + max);
        }
        return (int) count;
    }

    private static String formatUnits(RwxLocalGameTypes.UnitPage page, boolean compact,
                                      boolean json, boolean delta) {
        if (json) return formatUnitsJson(page);
        StringBuilder output = new StringBuilder("单位列表")
                .append(" (tick ").append(page.tick)
                .append(", time ").append(RwxCliText.time(page.gameTimeMillis)).append(')');
        appendSummary(output, page.units);
        if (delta) appendChanges(output, page.changes);
        output.append("\n单位 (").append(page.units.size()).append("):");
        for (RwxLocalGameTypes.UnitSnapshot unit : page.units) {
            output.append("\n").append(compact ? compactUnitLine(unit) : formatUnitLine(unit));
        }
        return output.toString();
    }

    /**
     * One line per unit for fast scanning: id, type, hp%, position, order and queue depth.
     * Deliberately loses tier/waypoint detail — {@code unit-show --id} still has everything.
     */
    private static String compactUnitLine(RwxLocalGameTypes.UnitSnapshot unit) {
        StringBuilder output = new StringBuilder("#").append(unit.id)
                .append(' ').append(unit.unitTypeId);
        float percent = unit.maxHealth > 0.0f
                ? Math.max(0.0f, Math.min(100.0f, unit.health * 100.0f / unit.maxHealth)) : 100.0f;
        if (percent < 99.95f) output.append(" hp").append(Math.round(percent)).append('%');
        if (unit.buildProgress < 0.999f) {
            output.append(" build").append(Math.round(unit.buildProgress * 100.0f)).append('%');
        }
        output.append(" @").append((int) unit.x).append(',').append((int) unit.y);
        if (unit.currentOrder != null) output.append(" | ").append(unit.currentOrder);
        if (unit.targetUnitId != null) output.append(" -> ").append(unit.targetUnitId);
        if (unit.queueSize != null && unit.queueSize > 0) {
            output.append(" | q").append(unit.queueSize);
        }
        String upgrading = upgradingLabel(unit);
        if (upgrading != null) output.append(" | upgrading ").append(upgrading);
        Float progress = currentProgress(unit);
        if (progress != null) output.append(" ").append(Math.round(progress * 100.0f)).append('%');
        return output.toString();
    }

    /**
     * {@code "T2 42%"} when the factory is mid-upgrade. Upgrade entries carry an action id
     * instead of a produced unit type, which is what separates them from production rows.
     */
    private static String upgradingLabel(RwxLocalGameTypes.UnitSnapshot unit) {
        for (RwxLocalGameTypes.QueueItem item : unit.productionQueue) {
            if ("produce".equals(item.command)) continue;
            String name = item.unitTypeName == null ? item.unitTypeId : item.unitTypeName;
            return name + (item.progress == null ? ""
                    : " " + Math.round(item.progress * 100.0f) + "%");
        }
        return null;
    }

    /** Machine-readable {@code unit-list --json}; a read-only view, no change baseline touched. */
    private static String formatUnitsJson(RwxLocalGameTypes.UnitPage page) {
        StringBuilder output = new StringBuilder();
        output.append("{\"tick\":").append(page.tick)
                .append(",\"time\":\"").append(RwxCliText.time(page.gameTimeMillis)).append('"')
                .append(",\"gameTimeMillis\":").append(page.gameTimeMillis)
                .append(",\"count\":").append(page.units.size())
                .append(",\"summary\":[");
        Map<String, Map<String, Integer>> summary = new LinkedHashMap<>();
        for (RwxLocalGameTypes.UnitSnapshot unit : page.units) {
            String key = unit.relation + "|" + unit.teamId;
            Map<String, Integer> counts = summary.get(key);
            if (counts == null) {
                counts = new LinkedHashMap<>();
                summary.put(key, counts);
            }
            Integer previous = counts.get(unit.unitTypeId);
            counts.put(unit.unitTypeId, previous == null ? 1 : previous + 1);
        }
        boolean firstGroup = true;
        for (Map.Entry<String, Map<String, Integer>> group : summary.entrySet()) {
            String[] parts = group.getKey().split("\\|", 2);
            if (!firstGroup) output.append(',');
            output.append("{\"relation\":\"").append(RwxCliText.json(parts[0]))
                    .append("\",\"team\":").append(parts[1]).append(",\"types\":{");
            boolean firstType = true;
            for (Map.Entry<String, Integer> entry : group.getValue().entrySet()) {
                if (!firstType) output.append(',');
                output.append('"').append(RwxCliText.json(entry.getKey())).append("\":")
                        .append(entry.getValue());
                firstType = false;
            }
            output.append("}}");
            firstGroup = false;
        }
        output.append("],\"units\":[");
        boolean firstUnit = true;
        for (RwxLocalGameTypes.UnitSnapshot unit : page.units) {
            if (!firstUnit) output.append(',');
            output.append("{\"id\":").append(unit.id)
                    .append(",\"type\":\"").append(RwxCliText.json(unit.unitTypeId)).append('"')
                    .append(",\"name\":\"").append(RwxCliText.json(unit.displayName)).append('"')
                    .append(",\"team\":").append(unit.teamId)
                    .append(",\"relation\":\"").append(RwxCliText.json(unit.relation)).append('"')
                    .append(",\"x\":").append(RwxCliText.number(unit.x))
                    .append(",\"y\":").append(RwxCliText.number(unit.y))
                    .append(",\"hp\":").append(RwxCliText.number(unit.health))
                    .append(",\"maxHp\":").append(RwxCliText.number(unit.maxHealth))
                    .append(",\"build\":").append(RwxCliText.number(unit.buildProgress))
                    .append(",\"tech\":").append(unit.techLevel)
                    .append(",\"upgrade\":").append(unit.upgradeLevel)
                    .append(",\"controllable\":").append(unit.controllable);
            if (unit.currentOrder != null) {
                output.append(",\"order\":\"").append(RwxCliText.json(unit.currentOrder)).append('"');
            }
            if (unit.queueSize != null) output.append(",\"queue\":").append(unit.queueSize);
            String upgrading = upgradingLabel(unit);
            if (upgrading != null) {
                output.append(",\"upgrading\":\"").append(RwxCliText.json(upgrading)).append('"');
            }
            output.append('}');
            firstUnit = false;
        }
        return output.append("]}").toString();
    }

    /** 按“阵营 + 队伍”汇总单位数量，例如：敌方(team 1) c_artillery x2。 */
    private static void appendSummary(StringBuilder output, List<RwxLocalGameTypes.UnitSnapshot> units) {
        if (units.isEmpty()) return;
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        for (RwxLocalGameTypes.UnitSnapshot unit : units) {
            String key = unit.relation + "|" + unit.teamId;
            Map<String, Integer> counts = groups.get(key);
            if (counts == null) {
                counts = new LinkedHashMap<>();
                groups.put(key, counts);
            }
            Integer previous = counts.get(unit.unitTypeId);
            counts.put(unit.unitTypeId, previous == null ? 1 : previous + 1);
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort((left, right) -> {
            int byRelation = Integer.compare(relationRank(left), relationRank(right));
            return byRelation != 0 ? byRelation : left.compareTo(right);
        });
        output.append("\n摘要:");
        boolean first = true;
        for (String key : keys) {
            String relation = key.substring(0, key.indexOf('|'));
            String teamId = key.substring(key.indexOf('|') + 1);
            output.append(first ? " " : " | ")
                    .append(relationLabel(relation)).append("(team ").append(teamId).append(") ");
            boolean firstType = true;
            for (Map.Entry<String, Integer> entry : groups.get(key).entrySet()) {
                if (!firstType) output.append(", ");
                output.append(entry.getKey()).append(" x").append(entry.getValue());
                firstType = false;
            }
            first = false;
        }
    }

    private static int relationRank(String key) {
        String relation = key.substring(0, key.indexOf('|'));
        switch (relation) {
            case "self":
                return 0;
            case "ally":
                return 1;
            case "enemy":
                return 2;
            default:
                return 3;
        }
    }

    /** 上次 unit-list 之后出现的变化：先给“阵营 单位 xN 结局”的汇总，再列具体 id。 */
    private static void appendChanges(StringBuilder output, List<RwxLocalGameTypes.UnitChange> changes) {
        if (changes.isEmpty()) return;
        output.append("\n变化 (").append(changes.size()).append("):");
        for (RwxLocalGameTypes.UnitChange change : changes) {
            output.append("\n  ").append(change.unit.unitTypeId).append("(id=").append(change.unit.id)
                    .append(") ").append(change.description);
        }
    }

    private static String formatUnit(RwxLocalGameTypes.UnitSnapshot unit) {
        StringBuilder output = new StringBuilder("单位详情 (tick ")
                .append(unit.tick).append(")\n").append(formatUnitLine(unit));
        output.append("\nname: ").append(unit.displayName)
                .append("\ntier: T").append(unit.techLevel)
                .append("\nupgradeLevel: ").append(unit.upgradeLevel)
                .append("\nrelation: ").append(unit.relation)
                .append("\nmovement: ").append(unit.movementType)
                .append("\ncontrollable: ").append(unit.controllable);
        if (unit.attackMode != null) output.append("\nattackMode: ").append(unit.attackMode);
        if (unit.queueSize != null) output.append("\nqueue: ").append(unit.queueSize);
        String upgrading = upgradingLabel(unit);
        if (upgrading != null) output.append("\nupgrading: ").append(upgrading);
        if (unit.rallyX != null && unit.rallyY != null) {
            output.append("\nrally: ").append((int) (float) unit.rallyX)
                    .append(',').append((int) (float) unit.rallyY);
        }
        output.append("\ntransport: ").append(unit.transportId == null ? "none" : unit.transportId);
        appendQueue(output, "productionQueue", unit.productionQueue);
        appendQueue(output, "waypointQueue", unit.waypointQueue);
        return output.toString();
    }

    private static void appendQueue(StringBuilder output, String label,
                                    List<RwxLocalGameTypes.QueueItem> queue) {
        if (queue.isEmpty()) return;
        output.append('\n').append(label).append(" (").append(queue.size()).append("):");
        int index = 1;
        for (RwxLocalGameTypes.QueueItem item : queue) {
            output.append("\n  ").append(index++).append(". ").append(item.command);
            if (item.unitTypeId != null) {
                output.append(' ').append(item.unitTypeId);
                if (item.unitTypeName != null) output.append(" (").append(item.unitTypeName).append(')');
            }
            if (item.x != null && item.y != null) {
                output.append(" @").append((int) (float) item.x).append(',').append((int) (float) item.y);
            }
            if (item.targetUnitId != null) output.append(" target=").append(item.targetUnitId);
            if (item.progress != null) {
                output.append(" progress=").append(Math.round(item.progress * 100.0f)).append('%');
            }
        }
    }

    private static String formatUnitLine(RwxLocalGameTypes.UnitSnapshot unit) {
        StringBuilder output = new StringBuilder()
                .append("id: ").append(unit.id)
                .append(" | type: ").append(unit.unitTypeId)
                .append(" | team: ").append(unit.teamId)
                .append(" | pos: ").append((int) unit.x).append(',').append((int) unit.y)
                .append(" | hp: ").append((int) unit.health).append('/').append((int) unit.maxHealth)
                .append(" | build: ").append(unit.buildProgress)
                .append(" | tech: T").append(unit.techLevel);
        if (unit.upgradeLevel > 1) output.append(" | upgrade: ").append(unit.upgradeLevel);
        if (unit.currentOrder != null) output.append(" | order: ").append(unit.currentOrder);
        if (unit.attackMode != null) output.append(" | stance: ").append(unit.attackMode);
        if (unit.targetUnitId != null) output.append(" | target: ").append(unit.targetUnitId);
        if (unit.waypointX != null && unit.waypointY != null) {
            output.append(" | waypoint: ").append((int) (float) unit.waypointX)
                    .append(',').append((int) (float) unit.waypointY);
        }
        if (unit.waypointQueue.size() > 1) output.append(" | queue: ").append(unit.waypointQueue.size());
        if (unit.queueSize != null && unit.queueSize > 0) output.append(" | production: ").append(unit.queueSize);
        Float progress = currentProgress(unit);
        if (progress != null) {
            output.append(" | progress: ").append(Math.round(progress * 100.0f)).append('%');
        }
        return output.toString();
    }

    /** First active production/build entry's progress, if any. */
    private static Float currentProgress(RwxLocalGameTypes.UnitSnapshot unit) {
        if (!unit.productionQueue.isEmpty() && unit.productionQueue.get(0).progress != null) {
            return unit.productionQueue.get(0).progress;
        }
        if (!unit.waypointQueue.isEmpty() && unit.waypointQueue.get(0).progress != null) {
            return unit.waypointQueue.get(0).progress;
        }
        return null;
    }

    private static String optionalValue(List<String> arguments, String flag) {
        String value = null;
        for (int index = 0; index < arguments.size(); index++) {
            if (!flag.equalsIgnoreCase(arguments.get(index))) continue;
            if (value != null || index + 1 >= arguments.size() || arguments.get(index + 1).startsWith("--")) {
                throw new IllegalArgumentException(flag + " requires exactly one value");
            }
            value = arguments.get(index + 1);
        }
        return value;
    }

    private static String requiredOption(List<String> arguments, String flag) {
        String value = optionalValue(arguments, flag);
        if (value == null) throw new IllegalArgumentException(flag + " is required");
        return value;
    }

    private static final class UnitSelection {
        final List<Long> ids;
        final String selector;

        UnitSelection(List<Long> ids, String selector) {
            this.ids = ids;
            this.selector = selector;
        }
    }

    /** Accepts an id list, or a selector such as {@code all-army} / {@code group:main}. */
    private static UnitSelection parseUnitSelection(List<String> arguments) {
        String raw = requiredOption(arguments, "--units");
        List<Long> unitIds = new ArrayList<>();
        for (String value : raw.split(",", -1)) {
            String token = value.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException(
                        "--units must be a comma-separated id list (or all-army / all-units / group:<name>)");
            }
            try {
                unitIds.add(Long.parseLong(token));
            } catch (NumberFormatException notAnId) {
                // Any non-numeric token makes this a selector; the service validates the exact
                // selector form so typos get one clear error instead of "not an integer".
                return new UnitSelection(java.util.Collections.<Long>emptyList(), raw.trim());
            }
        }
        return new UnitSelection(unitIds, null);
    }

    private static boolean optionalFlag(List<String> arguments, String flag) {
        boolean found = false;
        for (String argument : arguments) {
            if (flag.equalsIgnoreCase(argument)) {
                if (found) throw new IllegalArgumentException(flag + " may be specified once");
                found = true;
            }
        }
        return found;
    }

    private static void validateOptions(List<String> arguments, String[] valueOptions, String... flagOptions) {
        java.util.HashSet<String> values = new java.util.HashSet<>();
        for (String option : valueOptions) values.add(option);
        java.util.HashSet<String> flags = new java.util.HashSet<>();
        for (String option : flagOptions) flags.add(option);
        for (int index = 0; index < arguments.size(); index++) {
            String token = arguments.get(index);
            String normalized = token.toLowerCase(Locale.ROOT);
            if (values.contains(normalized)) {
                if (index + 1 >= arguments.size() || arguments.get(index + 1).startsWith("--")) {
                    throw new IllegalArgumentException(token + " requires exactly one value");
                }
                index++;
            } else if (flags.contains(normalized)) {
                // Boolean flag: no following argv value.
            } else if (token.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + token);
            } else {
                throw new IllegalArgumentException("Unexpected argument: " + token);
            }
        }
    }

    private static void rejectUnknownFlags(List<String> arguments, String... known) {
        java.util.HashSet<String> accepted = new java.util.HashSet<>();
        for (String flag : known) accepted.add(flag);
        for (int index = 0; index < arguments.size(); index++) {
            String token = arguments.get(index);
            if (token.startsWith("--")) {
                if (!accepted.contains(token.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Unknown option: " + token);
                }
                index++;
            }
        }
    }

    private static Float parseFloat(String flag, String value) {
        try {
            float parsed = Float.parseFloat(value);
            if (!Float.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(flag + " must be a finite number");
        }
    }

    private static double parseDouble(String flag, String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(flag + " must be a finite number");
        }
    }

    private static String requiredFloatOption(List<String> arguments, String flag) {
        String value = optionalValue(arguments, flag);
        rejectUnknownFlags(arguments, flag);
        if (value == null) throw new IllegalArgumentException(flag + " is required");
        return value;
    }

    private static boolean parseBooleanOption(List<String> arguments, String flag) {
        String value = optionalValue(arguments, flag);
        rejectUnknownFlags(arguments, flag);
        if (value == null) throw new IllegalArgumentException(flag + " is required (true or false)");
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(flag + " must be true or false");
    }

    private static long parseLong(String flag, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(flag + " must be an integer");
        }
    }

    private static long parseOptionalLong(List<String> arguments, String flag, long defaultValue) {
        String value = optionalValue(arguments, flag);
        return value == null ? defaultValue : parseLong(flag, value);
    }

    private static int parseOptionalInt(List<String> arguments, String flag, int defaultValue) {
        long value = parseOptionalLong(arguments, flag, defaultValue);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(flag + " is out of range");
        }
        return (int) value;
    }

    /** Like {@link #parseOptionalInt} but distinguishes "absent" (null) from an explicit value. */
    private static Integer optionalIntegerArgument(List<String> arguments, String flag) {
        String raw = optionalValue(arguments, flag);
        if (raw == null) return null;
        long value = parseLong(flag, raw);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(flag + " is out of range");
        }
        return (int) value;
    }

    private static long requiredLongOption(List<String> arguments, String flag) {
        String value = optionalValue(arguments, flag);
        rejectUnknownFlags(arguments, flag);
        if (value == null) throw new IllegalArgumentException(flag + " is required");
        return parseLong(flag, value);
    }

    private static List<RwxLocalGameTypes.EventType> parseEventTypes(String raw, String option) {
        if (raw == null) return null;
        List<RwxLocalGameTypes.EventType> types = new ArrayList<>();
        for (String token : raw.split(",", -1)) {
            try {
                RwxLocalGameTypes.EventType type = RwxLocalGameTypes.EventType.valueOf(token.trim().toUpperCase(Locale.ROOT));
                if (!types.contains(type)) types.add(type);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(option + " must contain damaged,destroyed,losses,info,new");
            }
        }
        return types;
    }

    private static String formatReplay(RwxLocalGameTypes.ReplaySnapshot replay) {
        StringBuilder output = new StringBuilder("回放录制")
                .append("\ngameActive: ").append(replay.gameActive)
                .append("\nrecording: ").append(replay.recording)
                .append("\nreplaying: ").append(replay.replaying)
                .append("\narmedForNextMatch: ").append(replay.armed)
                .append("\ntick: ").append(replay.tick)
                .append("\ncommands: ").append(replay.commandCount);
        if (replay.filePath != null) {
            output.append("\nfile: ").append(replay.filePath);
            if (replay.absolutePath != null) output.append("\npath: ").append(replay.absolutePath);
            if (replay.fileSizeBytes > 0) output.append("\nsize: ").append(replay.fileSizeBytes).append(" bytes");
        } else {
            output.append("\nfile: none");
        }
        if (replay.message != null) output.append("\nmessage: ").append(replay.message);
        return output.toString();
    }

    private static String formatThreats(RwxLocalGameTypes.ThreatSnapshot threats, boolean json) {
        if (json) return formatThreatsJson(threats);
        StringBuilder output = new StringBuilder("威胁汇总")
                .append("\nunits: positions/distance/range in world units (1 tile = 20, space = 60)")
                .append("\ncenter: ").append((int) threats.x).append(',').append((int) threats.y)
                .append(" | radius: ").append((int) threats.radius).append(" (world)")
                .append("\n敌方可见单位: ").append(threats.enemyCount)
                .append(" | air: ").append(threats.airCount)
                .append(" | ground: ").append(threats.groundCount)
                .append(" | buildings: ").append(threats.buildingCount)
                .append("\n能力: antiAir: ").append(threats.antiAirCount)
                .append(" | antiGround: ").append(threats.antiGroundCount)
                .append(" | antiSubmerged: ").append(threats.antiSubmergedCount);
        if (threats.nearestDistance >= 0.0f) {
            output.append("\nnearestDistance: ").append((int) threats.nearestDistance);
        }
        Map<String, Integer> byTeamType = new LinkedHashMap<>();
        for (RwxLocalGameTypes.ThreatUnit unit : threats.units) {
            String key = unit.teamId + "|" + unit.unitTypeId;
            Integer previous = byTeamType.get(key);
            byTeamType.put(key, previous == null ? 1 : previous + 1);
        }
        if (!byTeamType.isEmpty()) {
            output.append("\n按类型:");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : byTeamType.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 2);
                output.append(first ? " " : ", ")
                        .append("team ").append(parts[0]).append(' ').append(parts[1])
                        .append(" x").append(entry.getValue());
                first = false;
            }
        }
        if (threats.antiAirCount > 0 && threats.airCount == 0) {
            output.append("\n提示: 范围内没有敌方空中单位，但有 ").append(threats.antiAirCount)
                    .append(" 个对空单位。");
        }
        if (threats.airCount > 0 && threats.antiAirCount == 0) {
            output.append("\n提示: 范围内有敌方空中单位，但没有任何可见的对空单位。");
        }
        if (!threats.clusters.isEmpty()) {
            output.append("\n集群 (").append(threats.clusters.size())
                    .append(" 组, 连接距离 ").append((int) threats.clusterRadius).append(" world):");
            for (RwxLocalGameTypes.ThreatCluster cluster : threats.clusters) {
                output.append("\n  ").append(cluster.index).append(". 中心 ")
                        .append((int) cluster.centerX).append(',').append((int) cluster.centerY)
                        .append(" | 单位 ").append(cluster.unitCount)
                        .append(" (建筑 ").append(cluster.buildingCount)
                        .append(", 空中 ").append(cluster.airCount)
                        .append(", 地面 ").append(cluster.groundCount).append(')')
                        .append(" | HP ").append((int) cluster.health).append('/')
                        .append((int) cluster.maxHealth)
                        .append(" | 最近 ").append((int) cluster.nearestDistance)
                        .append(" | 最大射程 ").append((int) cluster.maxRange);
                StringBuilder tags = new StringBuilder();
                if (cluster.hasAntiAir) tags.append("antiAir ");
                if (cluster.hasAntiGround) tags.append("antiGround ");
                if (cluster.hasAntiSubmerged) tags.append("antiSub ");
                if (tags.length() > 0) output.append(" [").append(tags.toString().trim()).append(']');
                output.append("\n     兵种: ").append(compositionLabel(cluster.composition));
            }
        }
        int shown = 0;
        for (RwxLocalGameTypes.ThreatUnit unit : threats.units) {
            if (shown >= 30) {
                output.append("\n  … ").append(threats.units.size() - shown).append(" more");
                break;
            }
            output.append("\n  ").append(unit.displayName == null ? unit.unitTypeId : unit.displayName)
                    .append(" (").append(unit.unitTypeId).append(", id=").append(unit.id).append(")")
                    .append(" team=").append(unit.teamId)
                    .append(" dist=").append((int) unit.distance)
                    .append(" pos=").append((int) unit.x).append(',').append((int) unit.y)
                    .append(" hp=").append((int) unit.health).append('/').append((int) unit.maxHealth)
                    .append(" range=").append((int) unit.range);
            StringBuilder tags = new StringBuilder();
            if (unit.flying) tags.append("air ");
            if (unit.building) tags.append("building ");
            if (unit.antiAir) tags.append("antiAir ");
            if (unit.antiGround) tags.append("antiGround ");
            if (unit.antiSubmerged) tags.append("antiSub ");
            if (tags.length() > 0) output.append(" [").append(tags.toString().trim()).append(']');
            shown++;
        }
        return output.toString();
    }

    private static String compositionLabel(List<RwxLocalGameTypes.GroupTypeCount> composition) {
        StringBuilder text = new StringBuilder();
        for (RwxLocalGameTypes.GroupTypeCount entry : composition) {
            if (text.length() > 0) text.append(", ");
            text.append(entry.unitTypeId).append(" x").append(entry.count);
        }
        return text.length() == 0 ? "无" : text.toString();
    }

    private static String formatThreatsJson(RwxLocalGameTypes.ThreatSnapshot threats) {
        StringBuilder output = new StringBuilder("{")
                .append("\"center\":{\"x\":").append(RwxCliText.number(threats.x))
                .append(",\"y\":").append(RwxCliText.number(threats.y)).append('}')
                .append(",\"radius\":").append(RwxCliText.number(threats.radius))
                .append(",\"enemyCount\":").append(threats.enemyCount)
                .append(",\"air\":").append(threats.airCount)
                .append(",\"ground\":").append(threats.groundCount)
                .append(",\"buildings\":").append(threats.buildingCount)
                .append(",\"antiAir\":").append(threats.antiAirCount)
                .append(",\"antiGround\":").append(threats.antiGroundCount)
                .append(",\"antiSubmerged\":").append(threats.antiSubmergedCount)
                .append(",\"nearestDistance\":")
                .append(threats.nearestDistance < 0.0f ? "null" : RwxCliText.number(threats.nearestDistance))
                .append(",\"clusterRadius\":").append(RwxCliText.number(threats.clusterRadius))
                .append(",\"clusters\":[");
        boolean first = true;
        for (RwxLocalGameTypes.ThreatCluster cluster : threats.clusters) {
            if (!first) output.append(',');
            output.append("{\"index\":").append(cluster.index)
                    .append(",\"x\":").append(RwxCliText.number(cluster.centerX))
                    .append(",\"y\":").append(RwxCliText.number(cluster.centerY))
                    .append(",\"radius\":").append(RwxCliText.number(cluster.radius))
                    .append(",\"units\":").append(cluster.unitCount)
                    .append(",\"air\":").append(cluster.airCount)
                    .append(",\"ground\":").append(cluster.groundCount)
                    .append(",\"buildings\":").append(cluster.buildingCount)
                    .append(",\"hp\":").append(RwxCliText.number(cluster.health))
                    .append(",\"maxHp\":").append(RwxCliText.number(cluster.maxHealth))
                    .append(",\"nearestDistance\":").append(RwxCliText.number(cluster.nearestDistance))
                    .append(",\"maxRange\":").append(RwxCliText.number(cluster.maxRange))
                    .append(",\"antiAir\":").append(cluster.hasAntiAir)
                    .append(",\"antiGround\":").append(cluster.hasAntiGround)
                    .append(",\"antiSubmerged\":").append(cluster.hasAntiSubmerged)
                    .append(",\"composition\":[");
            boolean firstType = true;
            for (RwxLocalGameTypes.GroupTypeCount entry : cluster.composition) {
                if (!firstType) output.append(',');
                output.append("{\"type\":\"").append(RwxCliText.json(entry.unitTypeId))
                        .append("\",\"count\":").append(entry.count).append('}');
                firstType = false;
            }
            output.append("],\"unitIds\":[");
            for (int index = 0; index < cluster.unitIds.size(); index++) {
                if (index > 0) output.append(',');
                output.append(cluster.unitIds.get(index));
            }
            output.append("]}");
            first = false;
        }
        output.append("],\"units\":[");
        first = true;
        for (RwxLocalGameTypes.ThreatUnit unit : threats.units) {
            if (!first) output.append(',');
            output.append("{\"id\":").append(unit.id)
                    .append(",\"type\":\"").append(RwxCliText.json(unit.unitTypeId)).append('"')
                    .append(",\"name\":\"").append(RwxCliText.json(unit.displayName)).append('"')
                    .append(",\"team\":").append(unit.teamId)
                    .append(",\"x\":").append(RwxCliText.number(unit.x))
                    .append(",\"y\":").append(RwxCliText.number(unit.y))
                    .append(",\"distance\":").append(RwxCliText.number(unit.distance))
                    .append(",\"hp\":").append(RwxCliText.number(unit.health))
                    .append(",\"maxHp\":").append(RwxCliText.number(unit.maxHealth))
                    .append(",\"range\":").append(RwxCliText.number(unit.range))
                    .append(",\"flying\":").append(unit.flying)
                    .append(",\"building\":").append(unit.building)
                    .append(",\"antiAir\":").append(unit.antiAir)
                    .append(",\"antiGround\":").append(unit.antiGround)
                    .append(",\"antiSubmerged\":").append(unit.antiSubmerged).append('}');
            first = false;
        }
        return output.append("]}").toString();
    }

    private static String relationLabel(String relation) {
        switch (relation) {
            case "self":
                return "我方";
            case "ally":
                return "盟友";
            case "enemy":
                return "敌方";
            case "neutral":
                return "中立";
            default:
                return relation == null ? "未知" : relation;
        }
    }

    private static String formatActions(long unitId, List<RwxLocalGameTypes.ActionSnapshot> actions,
                                        boolean json) {
        if (json) {
            StringBuilder output = new StringBuilder("{\"unit\":").append(unitId)
                    .append(",\"actions\":[");
            boolean first = true;
            for (RwxLocalGameTypes.ActionSnapshot action : actions) {
                if (!first) output.append(',');
                output.append("{\"id\":\"").append(RwxCliText.json(action.actionId)).append('"')
                        .append(",\"name\":\"").append(RwxCliText.json(action.name)).append('"')
                        .append(",\"available\":").append(action.available)
                        .append(",\"queued\":").append(action.queuedCount)
                        .append(",\"target\":\"").append(targetLabel(action)).append('"');
                if (action.price != null) {
                    output.append(",\"price\":\"").append(RwxCliText.json(action.price)).append('"');
                }
                if (action.producedUnitTypeId != null) {
                    output.append(",\"unitType\":\"")
                            .append(RwxCliText.json(action.producedUnitTypeId)).append('"');
                }
                if (action.unavailableReason != null) {
                    output.append(",\"reason\":\"")
                            .append(RwxCliText.json(action.unavailableReason)).append('"');
                }
                output.append('}');
                first = false;
            }
            return output.append("]}").toString();
        }
        StringBuilder output = new StringBuilder("可用动作 (unit ").append(unitId).append("):");
        for (RwxLocalGameTypes.ActionSnapshot action : actions) {
            output.append("\n")
                    .append("id: ").append(action.actionId)
                    .append(" | name: ").append(action.name)
                    .append(" | available: ").append(action.available)
                    .append(" | queued: ").append(action.queuedCount)
                    .append(" | target: ").append(targetLabel(action));
            if (action.price != null) output.append(" | price: ").append(action.price);
            if (action.unavailableReason != null) {
                output.append(" | unavailableReason: ").append(action.unavailableReason);
            }
            if (action.producedUnitTypeId != null) {
                output.append(" | unitType: ").append(action.producedUnitTypeId);
            }
        }
        return output.toString();
    }

    private static String targetLabel(RwxLocalGameTypes.ActionSnapshot action) {
        if (action.needsPointTarget) return "point";
        if (action.needsUnitTarget) return "unit";
        return "none";
    }

    /**
     * Issues one order, translating a permanent failure into a message that says so. Without this,
     * {@code --allow-unaffordable} looks broken when the real cause is a locked action or an
     * upgrade in progress: the flag only ever retries credit shortages.
     */
    private RwxLocalGameTypes.CommandReceipt issue(RwxLocalGameTypes.Order order) {
        try {
            return service.issue(order);
        } catch (IllegalArgumentException failure) {
            boolean retriedLater = RwxLocalGameTypes.isCreditShortage(failure)
                    || failure instanceof RwxEngineLocalGameService.UpgradeInProgressException;
            if (order.allowUnaffordable && !retriedLater) {
                throw new IllegalArgumentException(failure.getMessage()
                        + " —— 这不是资金问题，--allow-unaffordable 不会重试它");
            }
            throw failure;
        }
    }

    /**
     * {@code order-action --count N}: reports submitted / pending / failed per copy instead of
     * pretending an earlier copy was rolled back when a later one fails.
     */
    private String formatRepeatedAction(RwxLocalGameTypes.Order order) {
        int submitted = 0;
        int pending = 0;
        int failed = 0;
        String failure = null;
        int tick = -1;
        List<Long> units = new ArrayList<>();
        RwxLocalGameTypes.CommandReceipt last = null;
        for (int index = 0; index < order.repeatCount; index++) {
            try {
                RwxLocalGameTypes.CommandReceipt receipt = issue(order);
                last = receipt;
                tick = receipt.acceptedTick;
                units.addAll(receipt.affectedUnitIds);
                if (receipt.status == RwxLocalGameTypes.ReceiptStatus.PENDING) pending++;
                else submitted++;
            } catch (IllegalArgumentException error) {
                failed++;
                failure = error.getMessage();
                break;
            }
        }
        if (submitted == 0 && pending == 0) {
            throw new IllegalArgumentException("全部 " + order.repeatCount + " 条都失败了："
                    + (failure == null ? "未知原因" : failure));
        }
        RwxLocalGameTypes.ReceiptStatus status;
        if (failed > 0 || (submitted > 0 && pending > 0)) {
            status = RwxLocalGameTypes.ReceiptStatus.PARTIAL;
        } else if (pending > 0) {
            status = RwxLocalGameTypes.ReceiptStatus.PENDING;
        } else {
            status = RwxLocalGameTypes.ReceiptStatus.SUBMITTED;
        }
        StringBuilder output = new StringBuilder("Command accepted\nresult: ")
                .append(status.name().toLowerCase(Locale.ROOT))
                .append("\ntick: ").append(tick)
                .append("\nrequested: ").append(order.repeatCount)
                .append(" | submitted: ").append(submitted)
                .append(" | pending: ").append(pending)
                .append(" | failed: ").append(failed)
                .append("\nunits: ").append(RwxCliText.joinIds(units));
        if (last != null) output.append("\nmessage: ").append(last.message);
        if (failure != null) output.append("\nfailure: ").append(failure);
        return output.toString();
    }

    /**
     * Gather at a staging point, then attack-move. Two engine commands on one selection: a plain
     * move (which clears the old orders) followed by a queued attack-move, so the army advances
     * only after it has actually assembled.
     */
    private String push(List<String> arguments) {
        validateOptions(arguments,
                new String[] {"--units", "--group", "--x", "--y", "--rally-x", "--rally-y",
                        "--min-count", "--exclude", "--type", "--hp"},
                "--idle", "--exclude-builders");
        float targetX = parseFloat("--x", requiredOption(arguments, "--x"));
        float targetY = parseFloat("--y", requiredOption(arguments, "--y"));
        String rawRallyX = optionalValue(arguments, "--rally-x");
        String rawRallyY = optionalValue(arguments, "--rally-y");
        if ((rawRallyX == null) != (rawRallyY == null)) {
            throw new IllegalArgumentException("--rally-x and --rally-y must be given together");
        }
        int minCount = parseOptionalInt(arguments, "--min-count", 1);
        if (minCount < 1 || minCount > 200) {
            throw new IllegalArgumentException("--min-count must be an integer from 1 to 200");
        }
        String units = optionalValue(arguments, "--units");
        String group = optionalValue(arguments, "--group");
        if (units != null && group != null) {
            throw new IllegalArgumentException("Use either --units or --group, not both");
        }
        if (units == null && group == null) units = "all-army";
        List<Long> ids = new ArrayList<>();
        for (RwxLocalGameTypes.UnitSnapshot unit : service.units(new RwxLocalGameTypes.UnitQuery(
                RwxLocalGameTypes.UnitOwner.SELF, java.util.Collections.<String>emptyList(),
                null, null, null, optionalFlag(arguments, "--idle"), group, true,
                optionalFlag(arguments, "--exclude-builders"), units,
                includedTypes(arguments), excludedTypeNames(arguments), hpBelow(arguments))).units) {
            if (unit.controllable) ids.add(unit.id);
        }
        if (ids.size() < minCount) {
            throw new IllegalArgumentException("只有 " + ids.size() + " 个单位可用（--min-count "
                    + minCount + "）；先集结更多单位，或降低 --min-count");
        }
        List<RwxLocalGameTypes.CommandReceipt> receipts = new ArrayList<>();
        StringBuilder output = new StringBuilder("push: ").append(ids.size()).append(" units ")
                .append(RwxCliText.joinIds(ids));
        if (rawRallyX != null) {
            float rallyX = parseFloat("--rally-x", rawRallyX);
            float rallyY = parseFloat("--rally-y", rawRallyY);
            RwxLocalGameTypes.CommandReceipt rally = issue(new RwxLocalGameTypes.Order(
                    RwxLocalGameTypes.OrderType.MOVE, ids, null, null, null, null, null,
                    rallyX, rallyY, false, false));
            receipts.add(rally);
            output.append("\n集结: ").append((int) rallyX).append(',').append((int) rallyY)
                    .append("（先移动，到达后自动推进）");
        }
        RwxLocalGameTypes.CommandReceipt attack = issue(new RwxLocalGameTypes.Order(
                RwxLocalGameTypes.OrderType.ATTACK_MOVE, ids, null, null, null, null, null,
                targetX, targetY, !receipts.isEmpty(), false));
        receipts.add(attack);
        output.append("\n推进: attack-move ").append((int) targetX).append(',').append((int) targetY)
                .append(receipts.size() > 1 ? "（队列中，等集结完成）" : "");
        RwxLocalGameTypes.CommandReceipt last = receipts.get(receipts.size() - 1);
        output.append("\ntick: ").append(last.acceptedTick)
                .append("\nresult: ").append(last.status.name().toLowerCase(Locale.ROOT))
                .append("\nmessage: ").append(last.message);
        return output.toString();
    }

    private static String formatKeep(RwxLocalGameTypes.KeepPage page) {
        StringBuilder output = new StringBuilder("维持规则 (tick ").append(page.tick)
                .append(", time ").append(RwxCliText.time(page.gameTimeMillis)).append(')')
                .append("\ncount: ").append(page.rules.size())
                .append("\n说明: 采样器（250ms）在队列低于 count 时自动补产；只在动作可用且买得起时补，"
                        + "不会透支资金。");
        if (page.rules.isEmpty()) {
            output.append("\n当前没有维持规则。用 ")
                    .append(RwxCliCommands.Command.KEEP.commandName())
                    .append(" --unit <工厂id> --action <动作id> --count N 新建。");
        } else {
            for (RwxLocalGameTypes.KeepRuleSnapshot rule : page.rules) {
                output.append("\n  #").append(rule.id)
                        .append(" 单位 ").append(rule.unitId)
                        .append(" · ").append(rule.actionId)
                        .append(" x").append(rule.count)
                        .append("（当前 ").append(rule.queued).append("）")
                        .append(" | 已补 ").append(rule.issued)
                        .append(" | ").append(rule.state);
            }
        }
        if (!page.recent.isEmpty()) {
            output.append("\n最近动作（新→旧）:");
            for (String note : page.recent) output.append("\n  · ").append(note);
        }
        return output.toString();
    }

    private static String formatReceipt(RwxLocalGameTypes.CommandReceipt receipt) {
        return "Command accepted\n"
                + "result: " + receipt.status.name().toLowerCase(Locale.ROOT) + "\n"
                + "tick: " + receipt.acceptedTick + "\n"
                + "appliedImmediately: " + receipt.appliedImmediately + "\n"
                + "units: " + RwxCliText.joinIds(receipt.affectedUnitIds) + "\n"
                + "message: " + receipt.message;
    }

    // ---------------------------------------------------------------- 自动化规则（auto-*）

    private static final String[] AUTO_SET_VALUE_OPTIONS = {
            "--rule", "--enabled", "--units", "--group", "--type", "--exclude", "--hp", "--idle",
            "--x", "--y", "--radius", "--include-units"
    };
    private static final String[] AUTO_SET_FLAGS = {"--exclude-builders"};

    /** 把 {@code auto-set} 的命令行选项翻译成补丁；未出现的选项保持 null。 */
    private static RwxLocalGameTypes.AutoRuleOptions autoRuleOptions(List<String> arguments) {
        RwxLocalGameTypes.AutoRuleOptions options = new RwxLocalGameTypes.AutoRuleOptions();
        options.rule = requiredOption(arguments, "--rule");
        if (com.corrodinggames.rts.game.ai.custom.ai_automate.AiAutomateRuleId.byId(options.rule)
                == com.corrodinggames.rts.game.ai.custom.ai_automate.AiAutomateRuleId.ASSIST_COUNTER) {
            validateOptions(arguments, new String[] {"--rule", "--enabled"});
        }
        options.enabled = optionalBoolean(arguments, "--enabled");
        options.units = optionalValue(arguments, "--units");
        options.group = optionalValue(arguments, "--group");
        if (options.units != null && options.group != null) {
            throw new IllegalArgumentException("--units and --group cannot be combined");
        }
        options.excludedIds = excludedIds(arguments);
        options.includeTypes = includedTypes(arguments);
        options.excludeTypes = excludedTypeNames(arguments);
        options.hpPercent = hpBelow(arguments);
        options.idleOnly = optionalBoolean(arguments, "--idle");
        options.includeUnits = optionalBoolean(arguments, "--include-units");
        if (optionalFlag(arguments, "--exclude-builders")) options.excludeBuilders = Boolean.TRUE;
        String x = optionalValue(arguments, "--x");
        String y = optionalValue(arguments, "--y");
        if ((x == null) != (y == null)) {
            throw new IllegalArgumentException("Specify --x and --y together");
        }
        if (x != null) {
            options.x = parseFloat("--x", x);
            options.y = parseFloat("--y", y);
        }
        String radius = optionalValue(arguments, "--radius");
        if (radius != null) options.radius = parseFloat("--radius", radius);
        return options;
    }

    /** {@code --enabled}/{@code --idle}：缺省为 null，其余必须是 true/false。 */
    private static Boolean optionalBoolean(List<String> arguments, String flag) {
        String raw = optionalValue(arguments, flag);
        if (raw == null) return null;
        if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
        throw new IllegalArgumentException(flag + " must be true or false");
    }

    private static String formatAutoRules(RwxLocalGameTypes.AutoPage page) {
        StringBuilder output = new StringBuilder(page.message)
                .append(" (tick ").append(page.tick)
                .append(", time ").append(RwxCliText.time(page.gameTimeMillis)).append(')');
        for (RwxLocalGameTypes.AutoRuleSnapshot rule : page.rules) {
            output.append('\n')
                    .append(rule.enabled ? "[开] " : "[关] ")
                    .append(rule.id).append(" — ").append(rule.label)
                    .append("\n    ").append(rule.summary)
                    .append("\n    优先级 ").append(rule.priority)
                    .append(" | 候选 ").append(rule.eligible)
                    .append(" | 接管 ").append(rule.engaged)
                    .append(" | 触发 ").append(rule.triggers)
                    .append(" | ").append(rule.state);
            String parameters = autoRuleParameters(rule);
            if (!parameters.isEmpty()) output.append("\n    ").append(parameters);
        }
        if (!page.recent.isEmpty()) {
            output.append("\n最近触发（新→旧）:");
            for (String note : page.recent) output.append("\n  · ").append(note);
        }
        return output.toString();
    }

    private static String formatAutoRule(RwxLocalGameTypes.AutoPage page) {
        StringBuilder output = new StringBuilder(page.message)
                .append(" (tick ").append(page.tick)
                .append(", time ").append(RwxCliText.time(page.gameTimeMillis)).append(')');
        for (RwxLocalGameTypes.AutoRuleSnapshot rule : page.rules) {
            output.append('\n').append(rule.id).append(" — ").append(rule.label)
                    .append('\n').append(rule.summary)
                    .append("\n状态: ").append(rule.enabled ? "开启" : "关闭")
                    .append(" | ").append(rule.state)
                    .append("\n优先级: ").append(rule.priority)
                    .append(" | 冷却: ").append(rule.cooldownMillis / 1000f).append('s')
                    .append(" | 接管后恢复任务: ").append(rule.resume ? "是" : "否");
            String parameters = autoRuleParameters(rule);
            output.append("\n参数: ").append(parameters.isEmpty() ? "（默认）" : parameters);
            output.append("\n本局统计: 候选 ").append(rule.eligible)
                    .append(" | 当前接管 ").append(rule.engaged)
                    .append(" | 累计触发 ").append(rule.triggers);
            if (!rule.engagedUnitIds.isEmpty()) {
                output.append("\n接管单位: ").append(joinIdsShort(rule.engagedUnitIds, 20));
            }
        }
        if (!page.recent.isEmpty()) {
            output.append("\n最近触发（新→旧）:");
            for (String note : page.recent) output.append("\n  · ").append(note);
        }
        return output.toString();
    }

    /** 只显示实际设置过的参数，避免把默认值误读成“已配置”。 */
    private static String autoRuleParameters(RwxLocalGameTypes.AutoRuleSnapshot rule) {
        StringBuilder text = new StringBuilder();
        if (rule.units != null && !rule.units.trim().isEmpty()) text.append("units=").append(rule.units).append(' ');
        if (!rule.includeTypes.isEmpty()) text.append("type=").append(String.join(",", rule.includeTypes)).append(' ');
        if (!rule.excludeTypes.isEmpty()) text.append("exclude=").append(String.join(",", rule.excludeTypes)).append(' ');
        if (!rule.excludedIds.isEmpty()) text.append("excludeIds=").append(RwxCliText.joinIds(rule.excludedIds)).append(' ');
        if (rule.hpPercent != null) text.append("hp=").append(Math.round(rule.hpPercent)).append("% ");
        if (rule.radius != null) text.append("radius=").append(Math.round(rule.radius)).append(' ');
        if (rule.x != null && rule.y != null) {
            text.append("area=").append(Math.round(rule.x)).append(',').append(Math.round(rule.y)).append(' ');
        }
        if (rule.idleOnly) text.append("idleOnly ");
        if (rule.excludeBuilders) text.append("excludeBuilders ");
        if ("idle-alert".equals(rule.id)) text.append("includeUnits=").append(rule.includeUnits).append(' ');
        return text.toString().trim();
    }
}
