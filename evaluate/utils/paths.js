'use strict';
/**
 * 对局路径的唯一入口。
 *
 * 一局完整对局的全部材料只放在 `evaluate/output/<对局名>/` 里；仓库根 `replays/<对局名>.replay`
 * 是唯一允许留在外面的副本。所有脚本都通过本模块定位，不各自拼路径，这样引擎采集规则、评分
 * 口径或筛选条件变动时只需要改这里。
 *
 * 兼容旧布局：命令日志仍在 `evaluate/output/commands-<对局名>.txt`、引擎证据仍在
 * `evaluate/output/events-live/match-…` 时，本模块照样解析到同一个 `<对局名>/`，由
 * `tools/match_materials.js` 收敛成一个目录。
 */
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');
const OUTPUT = path.join(ROOT, 'evaluate', 'output');
const REPLAYS = path.join(ROOT, 'replays');
/** 归档区：`*_old` 旧材料与没跑完的零散局都收在这里，根目录只留当前对局。 */
const ARCHIVE = path.join(OUTPUT, '_archive');

/** `evaluate/output` 下的共享目录：永远不是一局的工作区。 */
const SHARED = new Set(['micro', 'events-live', 'session-cast', '_archive']);
/** 归档后缀：`*_old` 是"旧材料/旧版本"的约定，不参与任何自动处理。 */
const ARCHIVED = /_old$/i;

const isArchived = name => ARCHIVED.test(String(name));
const isShared = name => SHARED.has(String(name)) || isArchived(name) || String(name).startsWith('.');

function isDirectory(target) {
  try { return fs.statSync(target).isDirectory(); } catch { return false; }
}

function isFile(target) {
  try { return fs.statSync(target).isFile(); } catch { return false; }
}

/** `child` 是否等于 `parent` 或位于其内部。 */
function within(child, parent) {
  const relative = path.relative(path.resolve(parent), path.resolve(child));
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

/** `<对局名>` from `commands-<对局名>.txt`（`.jsonl` 同理）。 */
function matchNameOf(file) {
  const base = path.basename(String(file)).replace(/\.(txt|jsonl)$/i, '');
  const name = base.replace(/^commands?-?/i, '');
  if (!name) throw Error(`Command log filename must contain a match name: ${file}`);
  return name;
}

/**
 * Windows 安全的目录名：只替换真正非法的字符与空白，保留中文、括号和 `--`。
 *
 * `--` 必须保留：命令日志与对局目录的历史约定是 `<地图>-<日期>--<时-分>`，`tools/match_materials.js`
 * 也靠它把 `commands-…--09-29.txt` 对回 `…--09-29/`，折叠成单个 `-` 会让两边再也对不上。
 */
function sanitizeMatchName(value) {
  let name = String(value == null ? '' : value).trim();
  name = name.replace(/[<>:"/\\|?*\u0000-\u001f]+/g, '-').replace(/\s+/g, '-');
  name = name.replace(/^[-]+|[-.]+$/g, '');
  if (name.length > 96) name = name.slice(0, 96).replace(/[-.]+$/, '');
  return name;
}

/**
 * 解析一局的工作区目录。
 *
 * 接受三种输入，都返回绝对路径：工作区目录本身、命令日志（`commands-<名>.txt` 或
 * `prepared/commands-<名>.txt`）、以及对局名。命令日志已经在 `<root>/<对局名>/` 里时不再
 * 拼出第二层目录，避免"同一局两个目录"。
 */
function matchDirectory(source, root = OUTPUT) {
  const value = path.resolve(String(source));
  const base = path.resolve(root);
  if (isDirectory(value)) return value;
  const parent = path.dirname(value);
  const name = matchNameOf(value);
  if (within(parent, base) && (path.basename(parent) === name || path.basename(path.dirname(parent)) === name)) {
    return path.basename(parent) === name ? parent : path.dirname(parent);
  }
  return path.join(base, name);
}

/** `<root>/<对局名>/stage_<n>`。 */
function stageDirectory(commands, stage, root = OUTPUT) {
  return path.join(matchDirectory(commands, root), 'stage_' + stage);
}

/** 仓库根 `replays/<对局名>.replay`：与工作区目录同名，方便互相对照。 */
function replayFile(match, root = REPLAYS) {
  return path.join(path.resolve(root), path.basename(String(match)) + '.replay');
}

/** `<对局目录>/commands-<对局名>.txt`：原始命令日志在收敛后的规范位置。 */
function commandsFile(matchDir) {
  const dir = path.resolve(String(matchDir));
  return path.join(dir, `commands-${path.basename(dir)}.txt`);
}

/** `evaluate/output` 下直接子目录里哪些是真正的对局工作区。 */
function matchWorkspaces(root = OUTPUT) {
  const base = path.resolve(root);
  if (!isDirectory(base)) return [];
  return fs.readdirSync(base, {withFileTypes: true})
    .filter(entry => entry.isDirectory() && !isShared(entry.name))
    .map(entry => path.join(base, entry.name))
    .sort();
}

module.exports = {
  ROOT, OUTPUT, REPLAYS, ARCHIVE, SHARED,
  commandsFile, isArchived, isDirectory, isFile, isShared, matchDirectory, matchNameOf,
  matchWorkspaces, replayFile, sanitizeMatchName, stageDirectory, within,
};
