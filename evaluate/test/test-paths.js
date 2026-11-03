'use strict';
/**
 * `utils/paths.js` 是对局路径的唯一入口：它必须同时认"材料已收敛"和"材料还散着"两种布局，
 * 而且绝不能因为命令日志在 `<对局>/` 里就再拼出一层同名目录。
 */
const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const paths = require('../utils/paths');

function sandbox(t) {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), 'rwx-paths-'));
  t.after(() => fs.rmSync(base, {recursive: true, force: true}));
  const root = path.join(base, 'output');
  fs.mkdirSync(root, {recursive: true});
  return {base, root};
}

test('a command log resolves to its match directory from either layout', (t) => {
  const {root} = sandbox(t);
  const match = path.join(root, 'match-a');
  fs.mkdirSync(match);

  const loose = path.join(root, 'commands-match-a.txt');
  fs.writeFileSync(loose, '');
  assert.equal(paths.matchDirectory(loose, root), match);

  // Material already filed inside its own workspace must not become `<match>/<match>`.
  const inside = path.join(match, 'commands-match-a.txt');
  fs.writeFileSync(inside, '');
  assert.equal(paths.matchDirectory(inside, root), match);

  // `prepared/commands-<match>.txt` is the scoring input and still belongs to the same match.
  fs.mkdirSync(path.join(match, 'prepared'));
  const prepared = path.join(match, 'prepared', 'commands-match-a.txt');
  fs.writeFileSync(prepared, '');
  assert.equal(paths.matchDirectory(prepared, root), match);

  // A bare name and the directory itself resolve to the same place.
  assert.equal(paths.matchDirectory('match-a', root), match);
  assert.equal(paths.matchDirectory(match, root), match);
});

test('stage directories follow the command log, including the prepared input', (t) => {
  const {root} = sandbox(t);
  const match = path.join(root, 'match-a');
  fs.mkdirSync(path.join(match, 'prepared'), {recursive: true});
  const prepared = path.join(match, 'prepared', 'commands-match-a.txt');
  fs.writeFileSync(prepared, '');
  assert.equal(paths.stageDirectory(prepared, 1, root), path.join(match, 'stage_1'));
  assert.equal(paths.stageDirectory(prepared, 2, root), path.join(match, 'stage_2'));
});

test('an explicit output root still redirects reports away from the match directory', (t) => {
  const {base, root} = sandbox(t);
  const match = path.join(root, 'match-a');
  fs.mkdirSync(match);
  const inside = path.join(match, 'commands-match-a.txt');
  fs.writeFileSync(inside, '');
  const elsewhere = path.join(base, 'reports');
  // `tools/stage1_audit.js --out` must keep working: the workspace is only honoured under its root.
  assert.equal(paths.matchDirectory(inside, elsewhere), path.join(elsewhere, 'match-a'));
});

test('match names keep the double dash and parentheses the tooling keys on', () => {
  const name = paths.sanitizeMatchName('  Ice Island (2p) [v1.15] 20261103--09-29_重坦  ');
  assert.equal(name, 'Ice-Island-(2p)-[v1.15]-20261103--09-29_重坦');
  // Only the characters Windows rejects are replaced; path traversal cannot survive.
  assert.equal(paths.sanitizeMatchName('a/b\\c:d*e?f'), 'a-b-c-d-e-f');
  assert.equal(paths.sanitizeMatchName('   '), '');
  assert.ok(paths.sanitizeMatchName('x'.repeat(200)).length <= 96);
});

test('the replay file is named after the match directory, in the repository replays folder', () => {
  assert.equal(paths.replayFile('match-a'), path.join(paths.REPLAYS, 'match-a.replay'));
  assert.equal(paths.replayFile(path.join(paths.ROOT, 'out', 'match-a')), path.join(paths.REPLAYS, 'match-a.replay'));
  assert.equal(paths.replayFile('match-a', '/tmp/replays'), path.join(path.resolve('/tmp/replays'), 'match-a.replay'));
});

test('the command log inside a match keeps the match name', () => {
  assert.equal(paths.commandsFile('/tmp/out/match-a'),
    path.join(path.resolve('/tmp/out/match-a'), 'commands-match-a.txt'));
});

test('match workspaces skip shared, archived and helper directories', (t) => {
  const {root} = sandbox(t);
  for (const name of ['match-a', 'match-b_old', 'micro', 'events-live', 'events-live_old', '_archive', '.hidden']) {
    fs.mkdirSync(path.join(root, name), {recursive: true});
  }
  fs.writeFileSync(path.join(root, 'commands-match-c.txt'), '');
  assert.deepEqual(paths.matchWorkspaces(root).map(dir => path.basename(dir)), ['match-a']);
  assert.equal(paths.isArchived('4P_old'), true);
  assert.equal(paths.isShared('micro'), true);
  assert.equal(paths.isShared('_archive'), true);
  assert.equal(paths.isShared('match-a'), false);
});
