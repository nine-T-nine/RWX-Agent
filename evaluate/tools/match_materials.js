#!/usr/bin/env node
'use strict';
/**
 * 把一局完整对局的散落材料收敛到 `evaluate/output/<对局名>/`，并记录 `meta.json`。
 *
 *   node evaluate/tools/match_materials.js                    # 只打印计划（默认不改任何文件）
 *   node evaluate/tools/match_materials.js --apply            # 真的移动/改名/写 meta
 *   node evaluate/tools/match_materials.js <对局名或目录> --apply
 *   node evaluate/tools/match_materials.js --apply --meta run.json
 *   node evaluate/tools/match_materials.js --apply --set model=deepseek-v4.1-flash \
 *       --set provider=opencode-go --set thinking=high --set outcome=defeat --set tool=mcp
 *
 * 为什么需要它：一局打完，材料本来分散在四个地方——`evaluate/output/commands-<名>.txt`、
 * `evaluate/output/events-live/match-…/`、`evaluate/output/<名>/`（分阶段报告）、仓库根
 * `replays/<名>.replay`。评测做完经常两边都留一份，谁也说不清哪份是权威。本脚本只做三件事：
 *
 *   1. **移动而不是复制**：命令日志与引擎证据就地搬进对局目录；对局目录里已有同一份材料时，
 *      删掉外面那份（`replays/` 下的回放是唯一例外，永远留在仓库根，两份算一份）。
 *   2. **统一命名**：对局目录名就是这一局的名字；`replays/<名>.replay`、`<名>/commands-<名>.txt`、
 *      `<名>/events-live/<名>/` 都用同一个名字，脚本与人工都靠名字对照，不再靠时间戳猜。
 *   3. **记 `meta.json`**：系统版本、模型、工具、思考强度、地图、时长、胜负、会话、token 等。
 *      能从材料里读出来的自动填，读不出来的留 `null` 并标注来源，绝不用猜测填数。
 *
 * 安全边界：`*_old` 归档、`micro/`、`events-live_old/` 一律不碰；默认 dry-run；内容不一致时
 * 只报告冲突，不替用户挑一份删掉。
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {
  ARCHIVE, OUTPUT, REPLAYS, ROOT, commandsFile, isArchived, isDirectory, isFile,
  matchWorkspaces, replayFile, sanitizeMatchName,
} = require('../utils/paths');

const VERSION = 'match-meta-v1';
const META_FILE = 'meta.json';
/** 回放与实时证据的墙钟差多少秒以内算同一局（录像在关卡加载前就开始，通常差 0–2 秒）。 */
const JOIN_TOLERANCE_MS = 15000;
/** 命令日志的分钟级时间戳可以比开局早多久（房间配置与读图发生在开局前）。 */
const COMMANDS_LEAD_MS = 30 * 60 * 1000;
/** 微操（`evaluate/output/micro/`）的回放不属于完整对局，永远不参与收敛。 */
const MICRO_REPLAY = /^CASE\d+-[A-Za-z0-9]+_\d+_\d+_\d+_(?:realtime|pause|human)_s\d+_/i;

// --------------------------------------------------------------------- 通用

function readJson(file) {
  if (!isFile(file)) return null;
  try { return JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')); } catch { return null; }
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), {recursive: true});
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n', 'utf8');
}

/** 只读文件头，避免为了取一行 meta 而把 30 MB 的 telemetry 读进内存。 */
function headOf(file, bytes = 65536) {
  const fd = fs.openSync(file, 'r');
  try {
    const buffer = Buffer.alloc(bytes);
    const read = fs.readSync(fd, buffer, 0, bytes, 0);
    return buffer.subarray(0, read).toString('utf8');
  } finally { fs.closeSync(fd); }
}

function firstRow(file) {
  if (!isFile(file)) return null;
  const line = headOf(file).split('\n', 1)[0];
  if (!line || !line.startsWith('{')) return null;
  try { return JSON.parse(line); } catch { return null; }
}

function digest(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

/** 同内容（大小 + sha256）才算重复；不做"看起来一样"的猜测。 */
function sameFile(a, b) {
  if (!isFile(a) || !isFile(b)) return false;
  const left = fs.statSync(a), right = fs.statSync(b);
  return left.size === right.size && digest(a) === digest(b);
}

const pad = (value, width = 2) => String(value).padStart(width, '0');

// ------------------------------------------------------------- 时间戳解析

/** `match-001-20261103-093206-team0` 的墙钟部分。 */
function stampOfLiveDir(name) {
  const match = /^match-\d+-(\d{8}-\d{6})-team\d+/i.exec(name);
  return match ? match[1] : null;
}

const MONTHS = {jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12};

/**
 * 回放名里的开局时间：`(3 11月 2026 09.32.06)` / `(3 Nov 2026 09.32.06)`。
 * 引擎用 `Utility.formatCurrentDate("d MMM yyyy HH.mm.ss")`，月份写法跟着系统语言变。
 */
function stampOfReplay(stem) {
  const match = /\(\s*(\d{1,2})\s+([^\s()]+)\s+(\d{4})\s+(\d{1,2})\.(\d{2})\.(\d{2})\s*\)/.exec(stem);
  if (!match) return null;
  let month = MONTHS[match[2].slice(0, 3).toLowerCase()];
  if (!month) {
    const chinese = /^(\d{1,2})月$/.exec(match[2]);
    if (chinese) month = Number(chinese[1]);
  }
  if (!month) return null;
  return `${match[3]}${pad(month)}${pad(match[1])}-${pad(match[4])}${match[5]}${match[6]}`;
}

/** `commands-<地图>-20261103--09-29.txt` 只有分钟精度，秒补 00。 */
function stampOfCommandLog(name) {
  const match = /(\d{8})--(\d{2})-(\d{2})/.exec(name);
  return match ? `${match[1]}-${match[2]}${match[3]}00` : null;
}

/**
 * `startedAt` 的墙钟部分。它带时区（`2026-11-03T00:00:15.7+08:00`），但这里要的是"写文件那台
 * 机器的本地钟"——和目录名、回放名用的是同一个钟，所以只取字面值，不做时区换算。
 */
function stampOfIso(iso) {
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/.exec(String(iso ?? ''));
  return match ? `${match[1]}${match[2]}${match[3]}-${match[4]}${match[5]}${match[6]}` : null;
}

/** `yyyyMMdd-HHmmss` → 毫秒（按 UTC 解析即可，只用来比较差值）。 */
function stampMillis(stamp) {
  if (!/^\d{8}-\d{6}$/.test(String(stamp ?? ''))) return null;
  const value = Date.parse(`${stamp.slice(0, 4)}-${stamp.slice(4, 6)}-${stamp.slice(6, 8)}` +
    `T${stamp.slice(9, 11)}:${stamp.slice(11, 13)}:${stamp.slice(13, 15)}Z`);
  return Number.isFinite(value) ? value : null;
}

const dateOfStamp = stamp => (/^\d{8}/.test(stamp ?? '')
  ? `${stamp.slice(0, 4)}-${stamp.slice(4, 6)}-${stamp.slice(6, 8)}` : null);

const isoNow = () => {
  const when = new Date();
  const offset = -when.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  return `${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())}` +
    `T${pad(when.getHours())}:${pad(when.getMinutes())}:${pad(when.getSeconds())}` +
    `${sign}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`;
};

// ------------------------------------------------------------- 材料清点

function listCommandLogs(root) {
  if (!isDirectory(root)) return [];
  return fs.readdirSync(root)
    .filter(name => /^commands?-.*\.txt$/i.test(name))
    .filter(name => !isArchived(path.parse(name).name))
    .map(name => path.join(root, name))
    .sort();
}

function listLiveDirs(root) {
  const live = path.join(root, 'events-live');
  if (!isDirectory(live)) return [];
  return fs.readdirSync(live, {withFileTypes: true})
    .filter(entry => entry.isDirectory() && !isArchived(entry.name))
    .map(entry => path.join(live, entry.name))
    .sort();
}

/** 只收完整对局的回放：带开局时间括号，或已经就叫某个对局名；微操回放一律跳过。 */
function listReplays(root, known = new Set()) {
  if (!isDirectory(root)) return [];
  return fs.readdirSync(root)
    .filter(name => /\.replay$/i.test(name) && !isArchived(path.parse(name).name))
    .map(name => path.join(root, name))
    .filter(file => {
      const stem = path.basename(file, '.replay');
      if (MICRO_REPLAY.test(stem)) return false;
      return known.has(stem) || stampOfReplay(stem) != null;
    })
    .sort();
}

/** 回放目录里已经导出的 `.txt` 头：版本、地图、队伍、时长都在里面。 */
function replayExport(matchDir) {
  const dir = path.join(matchDir, 'replay');
  if (!isDirectory(dir)) return null;
  const text = fs.readdirSync(dir).find(name => /\.txt$/i.test(name) && !/\.summary\.txt$/i.test(name));
  if (!text) return null;
  const body = fs.readFileSync(path.join(dir, text), 'utf8');
  const field = pattern => pattern.exec(body)?.[1]?.trim() ?? null;
  const duration = field(/^#\s*frames\s*:.*?duration:\s*([\d.]+)s/m);
  const status = field(/^#\s*status\s*:\s*(.+)$/m);
  return {
    file: path.join(dir, text),
    version: field(/^#\s*build\s*:\s*v?([^\s(]+)/m),
    mapFile: field(/^#\s*map\s*:\s*(.+)$/m),
    teams: (field(/^#\s*teams\s*:\s*(.+)$/m) ?? '').split(',').map(item => item.trim()).filter(Boolean),
    durationSeconds: duration == null ? null : Number(duration),
    status,
    // 只收完整录制：残缺回放的时长是"按标称 tick 估算"，不能当对局时长记录。
    complete: status != null && /完整/.test(status) && !/未完成/.test(status),
  };
}

/**
 * 描述一个引擎证据目录（`telemetry.jsonl` + `economy.log`）。
 * `label`、`startedAt`、`map` 是判定"这是哪一局"的核心字段。
 */
function describeEvidence(dir) {
  const telemetry = path.join(dir, 'telemetry.jsonl');
  if (!isFile(telemetry)) return null;
  const meta = firstRow(telemetry);
  if (!meta || meta.kind !== 'meta') return null;
  const startedAt = meta.startedAt ?? null;
  return {
    dir, name: path.basename(dir), telemetry, economy: path.join(dir, 'economy.log'),
    label: meta.label ?? null, startedAt,
    stamp: stampOfIso(startedAt) ?? stampOfLiveDir(path.basename(dir)),
    map: meta.map ?? null, size: fs.statSync(telemetry).size, meta,
  };
}

function evidenceIn(matchDir) {
  const found = [];
  for (const nested of ['events-live', 'engine']) {
    const base = path.join(matchDir, nested);
    if (!isDirectory(base)) continue;
    for (const entry of fs.readdirSync(base, {withFileTypes: true})) {
      if (!entry.isDirectory()) continue;
      const evidence = describeEvidence(path.join(base, entry.name));
      if (evidence) found.push({...evidence, root: nested});
    }
  }
  return found;
}

// ------------------------------------------------------------------ 分组

/** 回放名去掉 `[local]`、`[v1.15]` 与时间括号，剩下人类加的标签。 */
function replayLabel(stem) {
  return stem.replace(/^\[(local|net|network)\]\s*/i, '')
    .replace(/\s*\[v[^\]]*\]/i, '')
    .replace(/\(\s*\d{1,2}\s+[^\s()]+\s+\d{4}\s+[\d.]+\s*\)/g, '')
    .trim();
}

/** 没有对局目录时，按"地图-日期--时分"起名，和命令日志的历史写法一致。 */
function suggestNameFromReplay(stem) {
  const stamp = stampOfReplay(stem);
  const label = replayLabel(stem);
  if (!stamp) return sanitizeMatchName(label);
  const cut = label.search(/(?:\s{2,}|_)/);
  const map = cut > 0 ? label.slice(0, cut) : label;
  const suffix = cut > 0 ? label.slice(cut) : '';
  return sanitizeMatchName(`${map}-${stamp.slice(0, 8)}--${stamp.slice(9, 11)}-${stamp.slice(11, 13)}${suffix}`);
}

function suggestNameFromEvidence(evidence) {
  if (!evidence) return null;
  const stamp = evidence.stamp ?? stampOfLiveDir(evidence.name);
  if (!stamp) return null;
  return sanitizeMatchName(`${evidence.map ?? ''}-${stamp.slice(0, 8)}--${stamp.slice(9, 11)}-${stamp.slice(11, 13)}`);
}

/**
 * 一局 = 一份命令日志 + 一份引擎证据 + 一份回放。三者靠墙钟时间戳（±容差）配对；
 * 对不上就单独成组并标明"无法唯一确定"，由 `--match` 或人工决定。
 */
function collectGroups(root = OUTPUT, replaysRoot = REPLAYS) {
  const groups = [];
  const byName = new Map();

  function group(name, source) {
    if (name && byName.has(name)) return byName.get(name);
    const created = {
      key: name || `anonymous-${groups.length + 1}`, name: name || null, workspace: null,
      commands: [], live: [], replays: [], evidence: [],
      // `stamps` 只放引擎证据/回放的墙钟（真正的开局时刻）；命令日志的分钟级时间戳单独放
      // `logStamps`，否则同一批次里相隔几分钟的两份命令日志会被误当成同一局。
      stamps: new Set(), logStamps: new Set(), readonly: false,
      sources: [source], conflicts: [], derived: {},
    };
    groups.push(created);
    if (name) byName.set(name, created);
    return created;
  }

  function nearest(stamp, windowMs) {
    const millis = stampMillis(stamp);
    if (millis == null) return null;
    let best = null;
    for (const entry of groups) {
      for (const candidate of entry.stamps) {
        const delta = Math.abs((stampMillis(candidate) ?? Infinity) - millis);
        if (delta <= windowMs && (!best || delta < best.delta)) best = {entry, delta};
      }
    }
    return best ? best.entry : null;
  }

  for (const dir of matchWorkspaces(root)) {
    const entry = group(path.basename(dir), 'workspace');
    entry.workspace = dir;
    entry.evidence = evidenceIn(dir);
    for (const evidence of entry.evidence) if (evidence.stamp) entry.stamps.add(evidence.stamp);
    const replay = replayFile(path.basename(dir), replaysRoot);
    if (isFile(replay)) entry.replays.push(replay);
  }

  for (const dir of listLiveDirs(root)) {
    const evidence = describeEvidence(dir);
    const stamp = evidence?.stamp ?? stampOfLiveDir(path.basename(dir));
    const owner = stamp ? nearest(stamp, JOIN_TOLERANCE_MS) : null;
    if (owner) { owner.live.push(dir); if (stamp) owner.stamps.add(stamp); continue; }
    const entry = group(evidence?.label ?? null, 'events-live');
    entry.live.push(dir);
    if (stamp) entry.stamps.add(stamp);
    entry.derived.evidence = evidence;
  }

  for (const log of listCommandLogs(root)) {
    const name = path.parse(log).name.replace(/^commands?-?/i, '');
    // 命令日志可能是 `…_重坦.txt`，对局目录是 `…_重.txt`：先认名字，再认时间。
    const entry = byName.get(name) ?? nearest(stampOfCommandLog(name), COMMANDS_LEAD_MS) ?? group(name, 'commands');
    entry.commands.push(log);
    const stamp = stampOfCommandLog(name);
    if (stamp) entry.logStamps.add(stamp);
  }

  for (const replay of listReplays(replaysRoot, new Set(groups.map(entry => entry.name).filter(Boolean)))) {
    const stem = path.basename(replay, '.replay');
    // A replay already named after a workspace is that workspace's canonical copy.
    const canonical = byName.get(stem);
    if (canonical) {
      if (!canonical.replays.some(file => path.resolve(file) === path.resolve(replay))) canonical.replays.push(replay);
      continue;
    }
    const stamp = stampOfReplay(stem);
    const owner = (stamp && nearest(stamp, JOIN_TOLERANCE_MS)) ?? null;
    if (owner) {
      // A workspace already claimed its canonical `<名>.replay` above; do not list it twice.
      if (!owner.replays.some(file => path.resolve(file) === path.resolve(replay))) owner.replays.push(replay);
      continue;
    }
    // 只有回放、没有别的材料：它不属于任何一次评估，`replays/` 里的那一份就是唯一副本。
    // 这类组只用于报告，不建对局目录、也不改回放名（否则会把 `_old` 归档"复活"成新对局）。
    const entry = group(suggestNameFromReplay(stem), 'replay');
    entry.replays.push(replay);
    // 同名组可能已经带着别的材料；只有"确实只剩回放"的组才是只读的。
    entry.readonly = !entry.workspace && !entry.commands.length && !entry.live.length && !entry.evidence.length;
    if (stamp) entry.stamps.add(stamp);
  }

  for (const entry of groups) resolveName(entry);
  return groups;
}

function resolveName(entry) {
  if (!entry.name) {
    const name = (entry.commands[0] ? path.parse(entry.commands[0]).name.replace(/^commands?-?/i, '') : null)
      || entry.derived.evidence?.label
      || suggestNameFromEvidence(entry.derived.evidence)
      || (entry.replays[0] ? suggestNameFromReplay(path.basename(entry.replays[0], '.replay')) : null);
    // 已经存在的目录名与命令日志名一律原样使用：它们是外部约定，不能在这里"顺手美化"。
    if (name) entry.name = sanitizeMatchName(name);
  }
  if (!entry.name) entry.conflicts.push('无法从材料确定对局名（可用 --match 指定）');
}

// ------------------------------------------------------------------ 计划

/**
 * 一局要做的动作。全部是"移动/改名/删重复"，没有"复制"：
 * 同一份材料只允许存在一处，例外只有 `replays/<名>.replay`。
 */
function planGroup(entry, options = {}) {
  const root = options.root ?? OUTPUT, replays = options.replays ?? REPLAYS;
  const actions = [];
  // 只有回放、没有评估材料的组：唯一副本就在 `replays/`，不建目录也不改名。
  if (entry.readonly || !entry.name) return actions;
  const target = entry.workspace ? path.resolve(entry.workspace) : path.join(root, entry.name);
  if (!isDirectory(target)) actions.push({kind: 'mkdir', to: target});

  // 1. 命令日志：搬进对局目录并改名为 commands-<对局名>.txt。
  for (const log of entry.commands) {
    const to = commandsFile(target);
    if (path.resolve(log) === path.resolve(to)) continue;
    if (isFile(to)) {
      actions.push(sameFile(log, to)
        ? {kind: 'drop', from: log, reason: `对局目录已有同一份命令日志 ${path.basename(to)}`}
        : {kind: 'conflict', from: log, to, reason: '两处命令日志内容不同，保留现状'});
      continue;
    }
    actions.push({kind: 'move', from: log, to});
  }

  // 2. 引擎证据：搬进 <对局目录>/events-live/，目录名尽量就是对局名。
  const kept = entry.evidence.map(evidence => ({...evidence}));
  for (const dir of entry.live) {
    const evidence = describeEvidence(dir);
    const nested = kept.find(item => item.stamp && evidence?.stamp && item.stamp === evidence.stamp)
      ?? kept.find(item => item.name === path.basename(dir));
    if (nested) {
      const liveSize = evidence?.size ?? 0;
      if (liveSize > nested.size) {
        actions.push({kind: 'drop', from: nested.dir, reason: `外层证据更完整，替换 ${path.basename(nested.dir)}`});
        actions.push({kind: 'move', from: dir, to: nested.dir});
        nested.size = liveSize;
      } else {
        actions.push({kind: 'drop', from: dir, reason: `${path.basename(nested.dir)} 已收进对局目录，外层副本不必保留`});
      }
      continue;
    }
    const preferred = kept.length === 0 ? entry.name : path.basename(dir);
    const to = path.join(target, 'events-live', preferred);
    actions.push({kind: 'move', from: dir, to});
    kept.push({dir: to, root: 'events-live', name: preferred, size: evidence?.size ?? 0, stamp: evidence?.stamp ?? null});
  }

  // 3. 对局目录里的证据目录统一叫对局名（只有一份时；多队伍时保留原名以免互相覆盖）。
  if (kept.length === 1 && path.basename(kept[0].dir) !== entry.name) {
    actions.push({kind: 'rename', from: kept[0].dir, to: path.join(target, kept[0].root, entry.name)});
  }

  // 4. 回放：仓库根只留一份，且名字与对局目录一致。
  const canonical = replayFile(entry.name, replays);
  for (const replay of entry.replays) {
    if (path.resolve(replay) === path.resolve(canonical)) continue;
    if (isFile(canonical)) {
      actions.push(sameFile(replay, canonical)
        ? {kind: 'drop', from: replay, reason: `replays/${entry.name}.replay 已是同一份回放`}
        : {kind: 'conflict', from: replay, to: canonical, reason: '两份回放内容不同，保留现状'});
      continue;
    }
    actions.push({kind: 'move', from: replay, to: canonical});
  }

  // 5. 早先版本在预处理时把 preprocess.json 同时写进了对局根目录和 prepared/。
  const preparedPreprocess = path.join(target, 'prepared', 'preprocess.json');
  const rootPreprocess = path.join(target, 'preprocess.json');
  if (isFile(rootPreprocess) && isFile(preparedPreprocess) && sameFile(rootPreprocess, preparedPreprocess)) {
    actions.push({kind: 'drop', from: rootPreprocess, reason: 'prepared/preprocess.json 才是评分输入的唯一副本'});
  }

  actions.push({kind: 'meta', to: path.join(target, META_FILE)});
  return actions.map(action => ({...action, match: entry.name}));
}

// ------------------------------------------------------------------ 执行

function applyActions(actions, options = {}) {
  const log = [];
  for (const action of actions) {
    try {
      // `meta` 由调用方在材料落位之后写，这里只跳过；`conflict` 只报告，不动文件。
      if (action.kind === 'conflict' || action.kind === 'meta') { if (action.kind === 'conflict') log.push(action); }
      else if (action.kind === 'mkdir') { fs.mkdirSync(action.to, {recursive: true}); log.push(action); }
      else if (action.kind === 'move') {
        fs.mkdirSync(path.dirname(action.to), {recursive: true});
        fs.renameSync(action.from, action.to);
        log.push(action);
      } else if (action.kind === 'rename') { fs.renameSync(action.from, action.to); log.push(action); }
      else if (action.kind === 'drop') {
        if (options.keepOriginals) continue;
        fs.rmSync(action.from, {recursive: true, force: true});
        log.push(action);
      }
    } catch (error) {
      log.push({...action, kind: 'failed', reason: error.message});
    }
  }
  return log;
}

// ------------------------------------------------------------------ 归档

/**
 * 根目录里以 `_old` 结尾的条目搬进 `evaluate/output/_archive/`，名字原样保留。
 *
 * 归档不参与收敛（不配对、不改名、不写 `meta.json`），只换个位置：根目录只留当前对局，
 * 一眼能看出哪些是"最近在评的"。`archived` 前缀保持 `*_old` 后缀，`stage1_audit.js` 等
 * 依据后缀跳过它们的逻辑不用改。
 */
function archiveEntries(root = OUTPUT, archive = ARCHIVE) {
  const actions = [];
  if (!isDirectory(root)) return actions;
  for (const entry of fs.readdirSync(root, {withFileTypes: true})) {
    // Archives are `*_old` directories and `*_old.txt` / `*_old.jsonl` files: the suffix sits before
    // the extension on files, so both the name and the stem have to be checked.
    if (!isArchived(entry.name) && !isArchived(path.parse(entry.name).name)) continue;
    const from = path.join(root, entry.name);
    if (path.resolve(from) === path.resolve(archive)) continue;
    actions.push({kind: 'move', from, to: path.join(archive, entry.name),
      reason: '旧材料归档，根目录只留当前对局'});
  }
  return actions;
}

// -------------------------------------------------------------------- meta

function gitInfo(root = ROOT) {
  const git = path.join(root, '.git');
  try {
    const head = fs.readFileSync(path.join(git, 'HEAD'), 'utf8').trim();
    if (!head.startsWith('ref: ')) return {commit: head, branch: null};
    const ref = head.slice(5).trim();
    const branch = ref.replace(/^refs\/heads\//, '');
    const file = path.join(git, ref);
    if (isFile(file)) return {commit: fs.readFileSync(file, 'utf8').trim(), branch};
    const packed = path.join(git, 'packed-refs');
    if (isFile(packed)) {
      for (const line of fs.readFileSync(packed, 'utf8').split('\n')) {
        const [commit, name] = line.trim().split(/\s+/);
        if (name === ref) return {commit, branch};
      }
    }
    return {commit: null, branch};
  } catch { return {commit: null, branch: null}; }
}

/** `startedAt` 是唯一的墙钟；时长来自回放导出与预处理窗口。 */
function timingOf(entry, target) {
  const evidence = entry.evidence[0] ?? entry.derived?.evidence ?? null;
  const preprocess = readJson(path.join(target, 'prepared', 'preprocess.json'))
    ?? readJson(path.join(target, 'preprocess.json'));
  const exported = replayExport(target);
  return {
    startedAt: evidence?.startedAt ?? null,
    gameSeconds: exported?.complete ? exported.durationSeconds : null,
    replayStatus: exported?.status ?? null,
    scoreWindowSeconds: preprocess ? Number(preprocess.window?.endMs ?? 0) / 1000 : null,
    trimmedSeconds: preprocess ? Number(preprocess.window?.trimmedMs ?? 0) / 1000 : null,
    ending: preprocess?.ending?.kind ?? null,
    endingReason: preprocess?.ending?.reason ?? null,
  };
}

/**
 * 胜负：材料里能读出来的只有"投降/中止"；被击败与获胜只能看当场会话。所以默认留 null 并写
 * `source: preprocess`，`--set outcome=…` 可以覆盖。
 */
function resultOf(timing, previous) {
  if (previous?.outcome) return previous;
  if (timing.ending === 'surrender') {
    return {outcome: 'defeat', label: '主动投降', detail: null, source: 'preprocess', inferred: true};
  }
  if (timing.ending === 'aborted' || timing.ending === 'truncated') {
    return {outcome: 'unfinished', label: null, detail: null, source: 'preprocess', inferred: true};
  }
  return {outcome: null, label: null, detail: null, source: null, inferred: false};
}

function buildMeta(entry, target, options = {}) {
  const replays = options.replays ?? REPLAYS;
  const previous = readJson(path.join(target, META_FILE)) ?? {};
  const evidence = entry.evidence[0] ?? entry.derived?.evidence ?? null;
  const exported = replayExport(target);
  const canonical = path.basename(replayFile(entry.name, replays));
  const replay = isFile(replayFile(entry.name, replays)) ? canonical : null;
  const now = isoNow();
  const timing = timingOf(entry, target);
  const git = gitInfo();
  const original = options.originalReplay || previous.materials?.replay?.originalName || replay;
  const meta = {
    schema: 1,
    version: VERSION,
    generatedBy: 'evaluate/tools/match_materials.js',
    match: entry.name,
    date: dateOfStamp(stampOfIso(timing.startedAt)) ?? dateOfStamp([...entry.stamps][0])
      ?? dateOfStamp([...entry.logStamps][0]) ?? previous.date ?? null,
    createdAt: previous.createdAt ?? now,
    updatedAt: now,
    system: {
      // 引擎版本：新构建写进 telemetry meta，旧局从回放导出头的 `# build` 读。
      engineVersion: evidence?.meta?.gameVersion ?? exported?.version ?? previous.system?.engineVersion ?? null,
      commit: git.commit ?? previous.system?.commit ?? null,
      branch: git.branch ?? previous.system?.branch ?? null,
      tier: evidence?.meta?.tier ?? previous.system?.tier ?? null,
      alertDelivery: evidence?.meta?.alertDelivery ?? previous.system?.alertDelivery ?? null,
      speedProfile: evidence?.meta?.speedProfile ?? previous.system?.speedProfile ?? null,
      diagnostic: evidence?.meta?.diagnostic ?? previous.system?.diagnostic ?? null,
      leaderboardEligible: evidence?.meta?.leaderboardEligible ?? previous.system?.leaderboardEligible ?? null,
    },
    models: previous.models ?? [],
    tools: previous.tools ?? {profile: null, detail: null},
    map: {
      name: evidence?.map ?? previous.map?.name ?? null,
      file: exported?.mapFile ?? previous.map?.file ?? null,
      teams: exported?.teams?.length ? exported.teams : previous.map?.teams ?? [],
    },
    timing,
    result: resultOf(timing, previous.result),
    sessions: previous.sessions ?? [],
    usage: previous.usage ?? null,
    // 材料清单：路径相对对局目录，搬走整个目录也不会失效。
    materials: {
      commands: isFile(commandsFile(target)) ? path.basename(commandsFile(target)) : null,
      // 原始文件名可能带着人工标注（比如 `_重坦`、误写的 `tool1`），改名后仍留档备查。
      commandsOriginal: previous.materials?.commandsOriginal ?? options.originalCommands ?? null,
      evidence: (entry.evidence ?? []).map(item => path.relative(target, item.dir).replace(/\\/g, '/')),
      replay: replay ? {name: replay, originalName: original} : previous.materials?.replay ?? null,
      replayExport: exported ? path.relative(target, exported.file).replace(/\\/g, '/') : null,
      prepared: isDirectory(path.join(target, 'prepared')) ? 'prepared' : null,
      stages: ['stage_1', 'stage_2'].filter(stage => isDirectory(path.join(target, stage))),
      score: isFile(path.join(target, 'score.json')) ? 'score.json' : null,
    },
    notes: previous.notes ?? [],
  };
  return mergeMeta(meta, options.overrides ?? {});
}

/**
 * 点号路径写入。中间节点按对象建：`models`/`sessions`/`notes` 这些数组来自 meta 模板本身，
 * 数字段自然落在数组下标上；凭空出现的数字段退化成对象键，不会悄悄变成空数组。
 */
function assign(meta, dotted, value) {
  const parts = dotted.split('.').filter(Boolean);
  if (!parts.length) return meta;
  let cursor = meta;
  for (const part of parts.slice(0, -1)) {
    if (cursor[part] == null || typeof cursor[part] !== 'object') cursor[part] = {};
    cursor = cursor[part];
  }
  cursor[parts.at(-1)] = value;
  return meta;
}

/** CLI 的短名字映射到结构，避免让管理员记一长串点号。 */
const ALIASES = {
  model: 'models.0.id', provider: 'models.0.provider', thinking: 'models.0.thinking',
  reasoning: 'models.0.thinking', tool: 'tools.profile', toolDetail: 'tools.detail',
  outcome: 'result.outcome', resultLabel: 'result.label', resultDetail: 'result.detail',
  session: 'sessions.0.id', sessionProvider: 'sessions.0.provider', sessionArchive: 'sessions.0.archive',
  tokens: 'usage.outputTokens', inputTokens: 'usage.inputTokens', tokensSource: 'usage.source',
  note: 'notes.0', opponent: 'map.teams.0',
};

function mergeMeta(meta, overrides) {
  for (const [key, value] of Object.entries(overrides ?? {})) assign(meta, ALIASES[key] ?? key, value);
  return meta;
}

// -------------------------------------------------------------------- main

function parseArgs(argv) {
  const options = {apply: false, json: false, archive: false, match: null, metaFile: null,
    overrides: {}, keepOriginals: false};
  const override = pair => {
    const at = pair.indexOf('=');
    if (at < 0) throw Error(`--set 需要 key=value：${pair}`);
    let value = pair.slice(at + 1);
    try { value = JSON.parse(value); } catch { /* 保持字符串 */ }
    options.overrides[pair.slice(0, at).trim()] = value;
  };
  for (let index = 0; index < argv.length; index++) {
    const flag = argv[index];
    if (flag === '--apply') options.apply = true;
    else if (flag === '--json') options.json = true;
    else if (flag === '--archive') options.archive = true;
    else if (flag === '--keep-originals') options.keepOriginals = true;
    else if (flag === '--match') options.match = argv[++index];
    else if (flag === '--meta') options.metaFile = argv[++index];
    else if (flag === '--set') override(argv[++index] ?? '');
    else if (flag.startsWith('--set=')) override(flag.slice(6));
    else if (flag.startsWith('--')) throw Error(`未知参数：${flag}`);
    else options.match = flag;
  }
  if (options.metaFile) {
    const extra = readJson(path.resolve(options.metaFile));
    if (!extra) throw Error(`读不到 meta 文件：${options.metaFile}`);
    options.overrides = {...extra, ...options.overrides};
  }
  return options;
}

function select(groups, wanted) {
  if (!wanted) return groups;
  return groups.filter(entry => entry.name === wanted
    || (entry.workspace && path.resolve(entry.workspace) === path.resolve(wanted)));
}

/**
 * 收敛一局的材料。`root`/`replays` 可替换，测试与脚本复用同一套规则。
 * 返回 `{applied, matches}`；不打印，由 `main` 负责呈现。
 */
function consolidate(options = {}) {
  const root = options.root ?? OUTPUT, replays = options.replays ?? REPLAYS;
  // `--archive`：顺带把根目录的 `*_old` 收进 `_archive/`。默认不动归档。
  const legacy = options.archive ? archiveEntries(root, options.archiveDir ?? ARCHIVE) : [];
  const groups = select(collectGroups(root, replays), options.match);
  if (options.match && !groups.length) throw Error(`找不到对局：${options.match}`);

  if (!options.apply) {
    return {applied: false,
      archive: legacy,
      matches: groups.map(entry => ({match: entry.name, workspace: entry.workspace,
        readonly: entry.readonly, conflicts: entry.conflicts, actions: planGroup(entry, {root, replays})}))};
  }

  const matches = [];
  for (const entry of groups) {
    const target = entry.workspace ?? (entry.name ? path.join(root, entry.name) : null);
    // 命令日志与回放都会被改名成对局名，原始名字必须在动手前记下来。
    const expected = entry.name ? path.basename(commandsFile(path.join(root, entry.name))) : null;
    const originalCommands = entry.commands.map(file => path.basename(file)).find(name => name !== expected) ?? null;
    const originalReplay = entry.replays.map(file => path.basename(file, '.replay'))
      .find(name => name !== entry.name) ?? null;
    const log = applyActions(planGroup(entry, {root, replays}), options);
    if (target && entry.name && isDirectory(target)) {
      const fresh = collectGroups(root, replays).find(item => item.name === entry.name) ?? entry;
      writeJson(path.join(target, META_FILE), buildMeta(fresh, target,
        {...options, replays, originalCommands, originalReplay}));
      log.push({kind: 'meta', to: path.join(target, META_FILE)});
    }
    matches.push({match: entry.name, readonly: entry.readonly, actions: log});
  }
  return {applied: true, archive: applyActions(legacy, options), matches};
}

function main(argv) {
  const options = parseArgs(argv);
  const result = consolidate(options);
  if (options.json) console.log(JSON.stringify(result, null, 2));
  else if (result.applied) printApplied(result);
  else printPlan(result);
  return result;
}

function describeAction(action) {
  const relative = value => (value ? path.relative(ROOT, value).replace(/\\/g, '/') : '');
  if (action.kind === 'move' || action.kind === 'rename') {
    return `${action.kind.padEnd(7)} ${relative(action.from)} → ${relative(action.to)}`;
  }
  if (action.kind === 'drop') return `drop    ${relative(action.from)}    （${action.reason}）`;
  if (action.kind === 'mkdir') return `mkdir   ${relative(action.to)}`;
  if (action.kind === 'meta') return `meta    ${relative(action.to)}`;
  if (action.kind === 'conflict') return `CONFLICT ${relative(action.from)}    （${action.reason}）`;
  return `${action.kind} ${relative(action.from ?? action.to)}    （${action.reason ?? ''}）`;
}

function printPlan(result) {
  const summary = result.matches;
  let pending = 0, conflicts = 0, readonly = 0, settled = 0;
  if (result.archive.length) {
    console.log(`归档（${result.archive.length} 项，进 evaluate/output/_archive/）`);
    for (const action of result.archive) { pending++; console.log(`   ${describeAction(action)}`); }
  }
  for (const item of summary) {
    if (item.readonly) { readonly++; continue; }
    const onlyMeta = item.actions.length === 1 && item.actions[0].kind === 'meta';
    if (onlyMeta && isFile(item.actions[0].to)) { settled++; continue; }
    pending += item.actions.length;
    conflicts += item.conflicts.length;
    console.log(`${item.match ?? '（未命名）'}`);
    for (const conflict of item.conflicts) console.log(`   CONFLICT ${conflict}`);
    for (const action of item.actions) console.log(`   ${describeAction(action)}`);
  }
  console.log(`\n${summary.length - readonly} 局当前对局，${settled} 局材料已收敛，待执行 ${pending} 项动作，${conflicts} 项需人工判断。`);
  if (readonly) console.log(`${readonly} 份只有回放、没有评估材料：唯一副本就在 replays/，不建目录、不改名。`);
  if (pending) console.log('[dry run] 确认后加 --apply 执行。');
}

function printApplied(result) {
  let changed = 0, readonly = 0, applied = 0;
  if (result.archive.length) {
    console.log(`归档 ${result.archive.length} 项 → evaluate/output/_archive/`);
    changed += result.archive.length;
  }
  for (const item of result.matches) {
    if (item.readonly) { readonly++; continue; }
    applied++;
    console.log(`${item.match}: ${item.actions.length} 项`);
    for (const action of item.actions) {
      changed++;
      console.log(`   ${describeAction(action)}`);
    }
  }
  console.log(`\n完成 ${applied} 局，处理 ${changed} 项。`);
  if (readonly) console.log(`${readonly} 份只有回放、没有评估材料：留在 replays/，未改动。`);
}

if (require.main === module) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

module.exports = {
  COMMANDS_LEAD_MS, JOIN_TOLERANCE_MS, META_FILE, VERSION, applyActions, archiveEntries, buildMeta,
  collectGroups, consolidate, describeEvidence, gitInfo, main, mergeMeta, planGroup, replayLabel,
  sameFile, stampOfCommandLog, stampOfIso, stampOfLiveDir, stampOfReplay, suggestNameFromReplay,
};
