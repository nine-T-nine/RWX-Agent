'use strict';
/**
 * `tools/match_materials.js`：一局只允许存在一个目录，材料搬过去而不是复制，回放名跟目录名一致，
 * 并且 `meta.json` 记下这一局的身份。这里全部在临时目录里跑，不碰真实 `evaluate/output/`。
 */
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const materials = require('../tools/match_materials');

const STARTED = '2026-11-03T09:32:06.896676100+08:00';
const MATCH = 'Ice-Island-(2p)-20261103--09-29_重';
const LIVE = 'match-001-20261103-093206-team0';
const REPLAY = '[local] Ice Island (2p) [v1.15] (3 11月 2026 09.32.06)_重坦';

function sandbox(t) {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-materials-'));
  t.after(() => fs.rmSync(base, {recursive: true, force: true}));
  const root = path.join(base, 'output');
  const replays = path.join(base, 'replays');
  fs.mkdirSync(root, {recursive: true});
  fs.mkdirSync(replays, {recursive: true});
  return {base, root, replays};
}

function writeTelemetry(dir, {startedAt = STARTED, map = 'Ice Island (2p)'} = {}) {
  fs.mkdirSync(dir, {recursive: true});
  const rows = [
    {kind: 'meta', schema: 1, team: 0, map, startedAt, label: null, source: 'live', tier: 2,
      alertDelivery: 'l1', speedProfile: 'realtime', diagnostic: false, leaderboardEligible: true},
    {kind: 'frame', timeMs: 1000, tick: 62, seq: 2},
  ];
  fs.writeFileSync(path.join(dir, 'telemetry.jsonl'), rows.map(row => JSON.stringify(row)).join('\n') + '\n');
  fs.writeFileSync(path.join(dir, 'economy.log'), '4000(+36)\n');
}

function writeReplayExport(matchDir) {
  const dir = path.join(matchDir, 'replay');
  fs.mkdirSync(dir, {recursive: true});
  fs.writeFileSync(path.join(dir, `${REPLAY}.txt`), [
    `# file   : ${REPLAY}.replay`,
    '# size   : 258718 bytes',
    '# build  : v1.15 (versionCode 176, protocol 96)',
    '# map    : maps/skirmish/[p2]Ice Island (2p).tmx',
    '# teams  : 0=Player, 1=AI - Hard',
    '# frames : 76717  duration: 1227.5s  commands: 2516  chats: 1  resyncs: 0',
    '# status : 完整',
    '',
  ].join('\n'));
}

/**
 * `fs.cpSync` 在含非 ASCII 路径的 Node 22 上会让进程直接 fail-fast（0xC0000409），
 * 所以测试自己递归复制，不依赖它。
 */
function copyTree(from, to) {
  fs.mkdirSync(to, {recursive: true});
  for (const entry of fs.readdirSync(from, {withFileTypes: true})) {
    const source = path.join(from, entry.name), target = path.join(to, entry.name);
    if (entry.isDirectory()) copyTree(source, target);
    else fs.copyFileSync(source, target);
  }
}

/** 一局"材料还散着"的旧布局：命令日志在 output 根、证据在 events-live、回放在 replays。 */
function legacyMatch(t) {
  const box = sandbox(t);
  const matchDir = path.join(box.root, MATCH);
  const nested = path.join(matchDir, 'events-live', LIVE);
  writeTelemetry(nested);
  writeReplayExport(matchDir);

  const preprocess = {version: 'preprocess-v1', window: {startMs: 0, endMs: 1197632, trimmedMs: 26960},
    ending: {kind: 'surrender', reason: 'agent_surrendered'}, scoreEligible: true};
  fs.mkdirSync(path.join(matchDir, 'prepared'), {recursive: true});
  fs.writeFileSync(path.join(matchDir, 'prepared', 'preprocess.json'), JSON.stringify(preprocess));
  fs.writeFileSync(path.join(matchDir, 'preprocess.json'), JSON.stringify(preprocess));

  const commandsName = 'commands-Ice-Island-(2p)-20261103--09-29_重坦.txt';
  fs.writeFileSync(path.join(box.root, commandsName),
    '[00:01]<name=Player;player=0;faction=A(0);role=player;match=1><rwx-game-state --summary>\n');

  const loose = path.join(box.root, 'events-live', LIVE);
  copyTree(nested, loose);

  fs.writeFileSync(path.join(box.replays, `${REPLAY}.replay`), 'RIFF');
  return {...box, matchDir, commandsName};
}

test('loose materials are moved into the one match directory instead of being copied', (t) => {
  const box = legacyMatch(t);
  const result = materials.consolidate({root: box.root, replays: box.replays, apply: true});
  assert.equal(result.applied, true);
  assert.equal(result.matches.length, 1);
  assert.equal(result.matches[0].match, MATCH);

  // Command log: gone from the output root, moved under its match with the match name.
  assert.equal(fs.existsSync(path.join(box.root, box.commandsName)), false);
  const moved = path.join(box.matchDir, `commands-${MATCH}.txt`);
  assert.match(fs.readFileSync(moved, 'utf8'), /rwx-game-state/);

  // Engine evidence: the outer copy is gone, the kept one is named after the match.
  assert.equal(fs.existsSync(path.join(box.root, 'events-live', LIVE)), false);
  const evidence = path.join(box.matchDir, 'events-live', MATCH);
  assert.match(fs.readFileSync(path.join(evidence, 'telemetry.jsonl'), 'utf8'), /"kind":"meta"/);
  assert.ok(fs.existsSync(path.join(evidence, 'economy.log')));

  // Replay: still exactly one copy, in replays/, named after the directory.
  assert.equal(fs.existsSync(path.join(box.replays, `${REPLAY}.replay`)), false);
  assert.ok(fs.existsSync(path.join(box.replays, `${MATCH}.replay`)));

  // preprocess.json only survives where scoring reads it.
  assert.equal(fs.existsSync(path.join(box.matchDir, 'preprocess.json')), false);
  assert.ok(fs.existsSync(path.join(box.matchDir, 'prepared', 'preprocess.json')));
});

test('the meta file records identity, map, duration and the outcome the material proves', (t) => {
  const box = legacyMatch(t);
  materials.consolidate({root: box.root, replays: box.replays, apply: true});
  const meta = JSON.parse(fs.readFileSync(path.join(box.matchDir, 'meta.json'), 'utf8'));
  assert.equal(meta.match, MATCH);
  assert.equal(meta.version, materials.VERSION);
  assert.equal(meta.date, '2026-11-03');
  assert.equal(meta.system.engineVersion, '1.15');
  assert.equal(meta.map.name, 'Ice Island (2p)');
  assert.deepEqual(meta.map.teams, ['0=Player', '1=AI - Hard']);
  assert.equal(meta.timing.startedAt, STARTED);
  assert.equal(meta.timing.gameSeconds, 1227.5);
  assert.equal(meta.timing.replayStatus, '完整');
  assert.equal(meta.timing.scoreWindowSeconds, 1197.632);
  assert.equal(meta.timing.ending, 'surrender');
  assert.equal(meta.result.outcome, 'defeat');
  assert.equal(meta.result.inferred, true);
  // Both renames are recorded: the material keeps its history without keeping a second file.
  assert.equal(meta.materials.commands, `commands-${MATCH}.txt`);
  assert.equal(meta.materials.commandsOriginal, 'commands-Ice-Island-(2p)-20261103--09-29_重坦.txt');
  assert.equal(meta.materials.replay.name, `${MATCH}.replay`);
  assert.equal(meta.materials.replay.originalName, REPLAY);
  assert.deepEqual(meta.materials.evidence, [`events-live/${MATCH}`]);
  assert.equal(meta.materials.replayExport, `replay/${REPLAY}.txt`);
});

test('running it again changes nothing but the meta timestamp', (t) => {
  const box = legacyMatch(t);
  materials.consolidate({root: box.root, replays: box.replays, apply: true});
  const again = materials.consolidate({root: box.root, replays: box.replays, apply: true});
  const changes = again.matches[0].actions.filter(action => action.kind !== 'meta');
  assert.deepEqual(changes, []);
  // A dry run confirms the same thing. The match must stay writable: its canonical replay is
  // already named after the directory, and that must not demote it to a report-only group.
  const plan = materials.consolidate({root: box.root, replays: box.replays});
  assert.equal(plan.matches[0].readonly, false);
  assert.deepEqual(plan.matches[0].actions.map(action => action.kind), ['meta']);
});

test('operator metadata overrides the inference and survives a later run', (t) => {
  const box = legacyMatch(t);
  materials.consolidate({root: box.root, replays: box.replays, apply: true, match: MATCH,
    overrides: {model: 'deepseek-v4.1-flash', provider: 'opencode-go', thinking: 'high',
      tool: 'mcp', outcome: 'victory', tokens: 69901, note: '主基地被摧毁后投降'}});
  const target = path.join(box.matchDir, 'meta.json');
  const meta = JSON.parse(fs.readFileSync(target, 'utf8'));
  assert.deepEqual(meta.models, [{id: 'deepseek-v4.1-flash', provider: 'opencode-go', thinking: 'high'}]);
  assert.equal(meta.tools.profile, 'mcp');
  assert.equal(meta.result.outcome, 'victory');
  assert.equal(meta.usage.outputTokens, 69901);
  assert.deepEqual(meta.notes, ['主基地被摧毁后投降']);

  materials.consolidate({root: box.root, replays: box.replays, apply: true, match: MATCH});
  const kept = JSON.parse(fs.readFileSync(target, 'utf8'));
  assert.deepEqual(kept.models, meta.models);
  assert.equal(kept.result.outcome, 'victory');
  assert.equal(kept.createdAt, meta.createdAt);
});

test('archived and micro directories are never scanned or rewritten', (t) => {
  const {root, replays} = sandbox(t);
  fs.mkdirSync(path.join(root, 'legacy_old', 'events-live', 'match-001-20261101-101010-team0'), {recursive: true});
  fs.writeFileSync(path.join(root, 'legacy_old', 'events-live', 'match-001-20261101-101010-team0', 'telemetry.jsonl'), '');
  fs.mkdirSync(path.join(root, 'micro', 'CASE1-duel', 'CASE1-duel_1_1_1_realtime_s1_20261031-194954'), {recursive: true});
  const microReplay = path.join(replays, 'CASE1-duel_1_1_1_realtime_s1_20261031-194954.replay');
  fs.writeFileSync(microReplay, 'RIFF');

  const result = materials.consolidate({root, replays, apply: true});
  assert.deepEqual(result.matches, []);
  assert.ok(fs.existsSync(path.join(root, 'legacy_old')));
  assert.ok(fs.existsSync(path.join(root, 'micro', 'CASE1-duel')));
  assert.ok(fs.existsSync(microReplay));
});

test('a replay without evaluation material is reported, not turned into a match directory', (t) => {
  const {root, replays} = sandbox(t);
  const stem = '[local] Lake (2p) [v1.15] (24 Oct 2026 18.02.00)';
  const file = path.join(replays, `${stem}.replay`);
  fs.writeFileSync(file, 'RIFF');
  const plan = materials.consolidate({root, replays});
  const group = plan.matches.find(item => item.match === materials.suggestNameFromReplay(stem));
  assert.equal(group.readonly, true);
  assert.deepEqual(group.actions, []);
  materials.consolidate({root, replays, apply: true});
  assert.ok(fs.existsSync(file));
  assert.deepEqual(fs.readdirSync(root), []);
});

test('two different command logs for one match are reported, not silently replaced', (t) => {
  const {root, replays} = sandbox(t);
  const matchDir = path.join(root, 'match-a');
  fs.mkdirSync(matchDir, {recursive: true});
  writeTelemetry(path.join(matchDir, 'events-live', LIVE));
  fs.writeFileSync(path.join(matchDir, 'commands-match-a.txt'), 'inside\n');
  fs.writeFileSync(path.join(root, 'commands-match-a.txt'), 'outside\n');

  const plan = materials.consolidate({root, replays});
  assert.equal(plan.matches[0].actions.filter(action => action.kind === 'conflict').length, 1);
  materials.consolidate({root, replays, apply: true});
  assert.equal(fs.readFileSync(path.join(root, 'commands-match-a.txt'), 'utf8'), 'outside\n');
  assert.equal(fs.readFileSync(path.join(matchDir, 'commands-match-a.txt'), 'utf8'), 'inside\n');
});

test('replay timestamps are read in both locale spellings of the month', () => {
  assert.equal(materials.stampOfReplay('Ice Island (2p) [v1.15] (3 11月 2026 09.32.06)_重坦'),
    '20261103-093206');
  assert.equal(materials.stampOfReplay('[local] Lake (2p) [v1.15] (24 Oct 2026 18.02.00)'),
    '20261024-180200');
  assert.equal(materials.stampOfReplay('CASE1-duel_1_1_1_realtime_s1_20261031-194954'), null);
  assert.equal(materials.stampOfLiveDir(LIVE), '20261103-093206');
  assert.equal(materials.stampOfCommandLog('commands-Ice-Island-(2p)-20261103--09-29_重坦.txt'),
    '20261103-092900');
});

test('archived material is moved aside so the root only lists current matches', (t) => {
  const {root, replays} = sandbox(t);
  const archive = path.join(root, '_archive');
  fs.mkdirSync(path.join(root, 'match-old_old'), {recursive: true});
  fs.writeFileSync(path.join(root, 'match-old_old', 'stage_1.json'), '{"score":1}');
  fs.writeFileSync(path.join(root, 'commands-match-old_old.txt'), 'log\n');

  const options = {root, replays, archive: true, archiveDir: archive};
  const plan = materials.consolidate(options);
  assert.equal(plan.archive.length, 2);
  assert.deepEqual(plan.matches, []);

  materials.consolidate({...options, apply: true});
  assert.ok(fs.existsSync(path.join(archive, 'match-old_old', 'stage_1.json')));
  assert.ok(fs.existsSync(path.join(archive, 'commands-match-old_old.txt')));
  assert.deepEqual(fs.readdirSync(root).sort(), ['_archive']);
  // Nothing left to archive on the next run.
  assert.deepEqual(materials.consolidate(options).archive, []);
});

test('archives are left alone unless --archive is asked for', (t) => {
  const {root, replays} = sandbox(t);
  fs.mkdirSync(path.join(root, 'match-old_old'), {recursive: true});
  const result = materials.consolidate({root, replays});
  assert.deepEqual(result.archive, []);
  assert.ok(fs.existsSync(path.join(root, 'match-old_old')));
});

test('a match that does not exist is an error, not an empty plan', (t) => {
  const {root, replays} = sandbox(t);
  assert.throws(() => materials.consolidate({root, replays, match: 'no-such-match'}), /找不到对局/);
});
