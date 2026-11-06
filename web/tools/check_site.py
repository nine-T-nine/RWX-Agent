"""Validate deployable site paths, recording integrity, ledger data and GitHub size limits."""

import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

WEB = Path(__file__).resolve().parents[1]


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = []
        self.links = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            self.ids.append(attrs['id'])
        for name in ('src', 'href', 'poster'):
            if attrs.get(name):
                self.links.append(attrs[name])


def parse(name):
    page = Page()
    page.feed((WEB / name).read_text(encoding='utf-8'))
    assert len(page.ids) == len(set(page.ids)), 'Duplicate page IDs in ' + name
    for link in page.links:
        parsed = urlsplit(link)
        if parsed.scheme or parsed.netloc:
            continue
        if parsed.path:
            target = (WEB / unquote(parsed.path)).resolve()
            assert target.is_relative_to(WEB), 'A site asset escapes web/: ' + link
            assert target.is_file(), 'Missing site asset: ' + link
        elif parsed.fragment:
            assert parsed.fragment in page.ids, 'Missing page anchor in ' + name + ': ' + link
    return page


def check_ledger():
    data = json.loads((WEB / 'resource/results.json').read_text(encoding='utf-8'))
    assert data['schema'] == 2
    standard = data['standard']
    assert standard['version'] == 'total-v2'
    assert 0 <= standard['k'] <= 1
    matches = data['matches']
    assert len(matches) == data['overview']['matches'] >= 1
    assert len({item['id'] for item in matches}) == len(matches)
    ids = {item['id'] for item in matches}
    assert data['overview']['best']['id'] in ids
    for item in matches:
        for key in ('title', 'archive', 'date', 'result', 'scores', 'pairs', 'duration', 'stage3'):
            assert key in item, f'{item["id"]}: missing {key}'
        assert item['archive'] == 'old_v1.1'
        assert item['duration']['scoreSeconds'] > 0
        assert item['result']['outcome'] in {'win', 'defeat', 'draw', 'unknown'}
        assert item['pairs']
        for pair in item['pairs']:
            assert pair['agent']['model'] and pair['agent']['provider'] and pair['agent']['reasoning']
            assert pair['tool']['name'] and 'extra' in pair['tool']
        scores = item['scores']
        expected = scores['stage3'] * standard['k'] + scores['stage1V2'] * (1 - standard['k'])
        assert abs(scores['totalV2'] - expected) < 0.01, item['id'] + ': total-v2 does not match weighted stages'
        for name in ('totalV2', 'stage1V2', 'stage3'):
            assert 0 <= scores[name] <= 100
        assert set(item['stage3']['deductions']) == {'STAGE3_AMBUSH', 'STAGE3_BARRAGE', 'STAGE3_GRINDER', 'STAGE3_FUNDS'}
    ignored = data['ignored']
    assert len(ignored) == data['overview']['ignored'] >= 1, 'material gaps must be recorded'
    titles = {item['title'] for item in matches}
    for item in ignored:
        assert item['title'] and item['reason'] and item['note'] and item['relativePath']
        assert item['title'] not in titles, 'a match cannot be both ranked and ignored'
    assert data['method']['limits'], 'known limits must be recorded'
    return data


def check_scoring():
    data = json.loads((WEB / 'resource/scoring.json').read_text(encoding='utf-8'))
    assert data['schema'] == 1
    stages = data['fullMatch']['stages']
    assert [stage['id'] for stage in stages] == ['stage1', 'stage2', 'stage3']
    assert len(stages[0]['items']) == 3, 'Stage 1-v2 must list all 3 scoring groups'
    assert len(stages[1]['items']) == 6, 'Stage 2 must list all 6 deduction families'
    assert len(stages[2]['items']) == 4, 'Stage 3 must list all 4 deduction families'
    cases = data['microCases']
    assert [case['id'] for case in cases] == ['case1', 'case2', 'case3', 'case4']
    for group in [*stages, *cases]:
        assert group['version'] and group['items']
        for item in group['items']:
            assert item['name'] and item['formula'] and item['description']
            assert item['cap'] > 0
    for case in cases:
        assert case['environment'] and case['imageAlt'] and case['imageCaption']
        image = (WEB / case['image'].removeprefix('./')).resolve()
        assert image.is_relative_to(WEB) and image.is_file(), 'Missing CASE image: ' + case['image']
    return data


if __name__ == '__main__':
    page = parse('index.html')
    html = (WEB / 'index.html').read_text(encoding='utf-8')
    assert '<title>RWX-Agent：让智能体参与经典RTS游戏Rusted Warfare</title>' in html
    assert 'https://github.com/nine-T-nine/RWX-Agent' in html
    assert 'https://github.com/yomi2539/RWX' in html
    assert '并定期同步上游源码' in html

    ledger_page = parse('results.html')
    ledger_html = (WEB / 'results.html').read_text(encoding='utf-8')
    assert ledger_page.ids, 'Ledger page must expose anchor targets'
    assert '<title>评测结果｜RWX-Agent</title>' in ledger_html
    assert 'id="last-updated"' in ledger_html
    assert 'resource/results.json' in (WEB / 'results.js').read_text(encoding='utf-8')
    assert (WEB / 'results.js').is_file()
    assert './results.html' in html, 'Home page must link to the ledger'

    recordings = json.loads((WEB / 'resource/matches.json').read_text(encoding='utf-8'))
    assert len(recordings) == 4
    assert len({item['id'] for item in recordings}) == 4
    for item in recordings:
        video = WEB / item['video']
        assert video.stat().st_size == item['bytes'], 'Video size mismatch: ' + item['id']
        assert video.stat().st_size < 100 * 1024 * 1024, 'Video exceeds GitHub limit'
        assert hashlib.sha256(video.read_bytes()).hexdigest() == item['sha256'], 'Video checksum mismatch: ' + item['id']
        assert (WEB / item['poster']).is_file()
        assert item['video'] in html, 'Missing no-JavaScript video link: ' + item['id']

    ledger = check_ledger()
    scoring = check_scoring()

    deployed = [WEB / name for name in ('index.html', 'styles.css', 'app.js', 'results.html', 'results.js', 'favicon.svg', '.nojekyll',
                                        'resource/matches.json', 'resource/results.json', 'resource/scoring.json')]
    for folder in ('matches', 'posters', 'cases'):
        deployed.extend(p for p in (WEB / 'resource' / folder).rglob('*') if p.is_file())
    total = sum(path.stat().st_size for path in deployed)
    assert total < 1_000_000_000, 'Site exceeds GitHub Pages size limit'
    print(f'PASS: {len(recordings)} recordings, {ledger["overview"]["matches"]} old_v1.1 matches, '
          f'{len(scoring["microCases"])} micro cases, '
          f'{len(deployed)} deployable files, '
          f'{total / 1024 / 1024:.1f} MiB; local links, video checksums and ledger scores valid.')
